/*
 * Copyright 2023 Green Mushroom
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.chrisbanes.photoview;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.exifinterface.media.ExifInterface;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public class TilesProvider {
    private final BitmapRegionDecoder mDecoder;
    private final int mWidth;
    private final int mHeight;
    private final int mExifOrientation;
    private final Consumer<List<Tile>> mCallback;
    private final Map<Integer, List<Tile>> mSampleSizeToTileGrid = new HashMap<>();
    private final Rect mLastRequestRect = new Rect();
    private int mLastRequestSampleSize;
    private final ThreadLocal<Rect> mLocalRect = ThreadLocal.withInitial(Rect::new);
    private final MainThreadExecutor mMainThreadExecutor = new MainThreadExecutor();
    private final ArrayList<Tile> mTilesHit = new ArrayList<>();

    private static class MainThreadExecutor implements Executor {
        final Handler mHandler = new Handler(Looper.getMainLooper());

        MainThreadExecutor() {
        }

        @Override
        public void execute(@NonNull Runnable command) {
            mHandler.post(command);
        }
    }

    public TilesProvider(@NonNull ParcelFileDescriptor pfd, Consumer<List<Tile>> callback)
            throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mDecoder = BitmapRegionDecoder.newInstance(pfd);
        } else {
            mDecoder = BitmapRegionDecoder.newInstance(pfd.getFileDescriptor(), false);
        }
        mWidth = mDecoder.getWidth();
        mHeight = mDecoder.getHeight();
        ExifInterface exifInterface = new ExifInterface(pfd.getFileDescriptor());
        int orientationAttr = exifInterface.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        if (orientationAttr == ExifInterface.ORIENTATION_ROTATE_90) {
            mExifOrientation = 90;
        } else if (orientationAttr == ExifInterface.ORIENTATION_ROTATE_180) {
            mExifOrientation = 180;
        } else if (orientationAttr == ExifInterface.ORIENTATION_ROTATE_270) {
            mExifOrientation = 270;
        } else {
            // orientationAttr == ExifInterface.ORIENTATION_NORMAL ||
            // orientationAttr == ExifInterface.ORIENTATION_UNDEFINED
            mExifOrientation = 0;
        }
        mCallback = callback;
    }

    /**
     * Returns {@code true} if called on the main thread, {@code false} otherwise.
     */
    public static boolean isOnMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    /**
     * Throws an {@link java.lang.IllegalArgumentException} if called on a thread other than the main
     * thread.
     */
    public static void assertMainThread() {
        if (!isOnMainThread()) {
            throw new IllegalArgumentException("You must call this method on the main thread");
        }
    }

    /**
     * Throws an {@link java.lang.IllegalArgumentException} if called on the main thread.
     */
    public static void assertBackgroundThread() {
        if (isOnMainThread()) {
            throw new IllegalArgumentException("You must call this method on a background thread");
        }
    }

    /**
     * Determines the rotation to be applied to tiles, based on EXIF orientation or chosen setting.
     */
    public int getOrientation() {
        return mExifOrientation;
    }

    /**
     * Returns the original image's width
     */
    public int getWidth() {
        if (getOrientation() == 0 || getOrientation() == 180) {
            return mWidth;
        } else {
            return mHeight;
        }
    }

    /**
     * Returns the original image's height
     */
    public int getHeight() {
        if (getOrientation() == 0 || getOrientation() == 180) {
            return mHeight;
        } else {
            return mWidth;
        }
    }

    /**
     * Returns true if this region decoder has been recycled.
     * If so, then it is an error to try use its method.
     *
     * @return true if the region decoder has been recycled
     */
    public final boolean isRecycled() {
        return mDecoder.isRecycled();
    }

    /**
     * Frees up the memory associated with this region decoder, and mark the
     * region decoder as "dead", meaning it will throw an exception if decodeRegion(),
     * getWidth() or getHeight() is called.
     *
     * <p>This operation cannot be reversed, so it should only be called if you are
     * sure there are no further uses for the region decoder. This is an advanced call,
     * and normally need not be called, since the normal GC process will free up this
     * memory when there are no more references to this region decoder.
     */
    @MainThread
    public void recycle() {
        mDecoder.recycle();
        for (final Map.Entry<Integer, List<Tile>> entry : mSampleSizeToTileGrid.entrySet()) {
            for (final Tile tile : entry.getValue()) {
                if (tile.isAvailable()) {
                    tile.bitmap.recycle();
                }
            }
        }
        mSampleSizeToTileGrid.clear();
        mLocalRect.remove();
    }

    /**
     * Calculates sample size to fit the source image in given bounds.
     */
    private int calculateInSampleSize(float scale) {
        final int reqWidth = (int) (getWidth() * scale);
        final int reqHeight = (int) (getHeight() * scale);

        // Raw width and height of image
        int inSampleSize = 1;
        if (reqWidth == 0 || reqHeight == 0) {
            return 32;
        }

        if (getWidth() > reqWidth || getHeight() > reqHeight) {
            // Calculate ratios of width and height to requested width and height
            final int widthRatio = Math.round((float) getWidth() / (float) reqWidth);
            final int heightRatio = Math.round((float) getWidth() / (float) reqHeight);

            // Choose the smallest ratio as inSampleSize value, this will guarantee
            // a final image with both dimensions larger than or equal to the
            // requested width and height.
            inSampleSize = Math.min(widthRatio, heightRatio);
        }

        // We want the actual sample size that will be used, so round down to nearest power of 2.
        int power = 1;
        while (power * 2 < inSampleSize) {
            power = power * 2;
        }
        return power;
    }

    /**
     * Once source image and view dimensions are known, creates a map of sample size to tile grid.
     */
    private List<Tile> createTileGrid(int sampleSize, int viewWidth, int viewHeight) {
        int xTiles = 1;
        int yTiles = 1;
        int sTileWidth = getWidth() / xTiles;
        int sTileHeight = getHeight() / yTiles;
        int subTileWidth = sTileWidth / sampleSize;
        int subTileHeight = sTileHeight / sampleSize;
        while (subTileWidth > viewWidth * 1.25) {
            xTiles += 1;
            sTileWidth = getWidth() / xTiles;
            subTileWidth = sTileWidth / sampleSize;
        }
        while (subTileHeight > viewHeight * 1.25) {
            yTiles += 1;
            sTileHeight = getHeight() / yTiles;
            subTileHeight = sTileHeight / sampleSize;
        }
        List<Tile> tileGrid = new ArrayList<>(xTiles * yTiles);
        for (int x = 0; x < xTiles; x++) {
            for (int y = 0; y < yTiles; y++) {
                final Rect rect = new Rect(
                        x * sTileWidth,
                        y * sTileHeight,
                        x == xTiles - 1 ? getWidth() : (x + 1) * sTileWidth,
                        y == yTiles - 1 ? getHeight() : (y + 1) * sTileHeight
                );
                tileGrid.add(new Tile(rect));
            }
        }
        return tileGrid;
    }

    /**
     * Decodes a rectangle region in the image specified by rect.
     *
     * @param rect       The rectangle that specified the region to be decode.
     * @param sampleSize If set to a value > 1, requests the decoder to subsample the original
     *                   image, returning a smaller image to save memory. The sample size is
     *                   the number of pixels in either dimension that correspond to a single
     *                   pixel in the decoded bitmap. For example, inSampleSize == 4 returns
     *                   an image that is 1/4 the width/height of the original, and 1/16 the
     *                   number of pixels. Any value <= 1 is treated the same as 1. Note: the
     *                   decoder uses a final value based on powers of 2, any other value will
     *                   be rounded down to the nearest power of 2.
     * @return The decoded bitmap, or null if the image data could not be
     * decoded.
     * @throws IllegalArgumentException if {@link BitmapFactory.Options#inPreferredConfig}
     *                                  is {@link android.graphics.Bitmap.Config#HARDWARE}
     *                                  and {@link BitmapFactory.Options#inMutable} is set, if the specified color space
     *                                  is not {@link ColorSpace.Model#RGB RGB}, or if the specified color space's transfer
     *                                  function is not an {@link ColorSpace.Rgb.TransferParameters ICC parametric curve}
     */
    @WorkerThread
    @NonNull
    private Bitmap decodeRegion(@NonNull Rect rect, int sampleSize) {
        assertBackgroundThread();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        Bitmap bitmap = mDecoder.decodeRegion(rect, options);
        if (bitmap == null) {
            throw new RuntimeException("Skia image decoder returned null bitmap - " +
                    "image format may not be supported");
        }
        return bitmap;
    }

    @MainThread
    private void getTilesHit(@NonNull List<Tile> dst, @NonNull List<Tile> tileGrid,
                             @NonNull Rect displayRect) {
        for (final Tile tile : tileGrid) {
            if (tile.isAvailable() && Rect.intersects(tile.rect, displayRect)) {
                // Add other tiles to the front of the list
                // to prevent them from overlaying the requested tiles.
                dst.add(0, tile);
            }
        }
    }

    @SuppressWarnings("SuspiciousNameCombination")
    private Bitmap decodeRegionRotated(@NonNull Rect rect, int sampleSize) {
        final Rect localRect = mLocalRect.get();
        assert localRect != null;
        switch (getOrientation()) {
            case 0:
                localRect.set(rect);
                break;
            case 90:
                localRect.set(rect.top, getHeight() - rect.right,
                        rect.bottom, getHeight() - rect.left);
                break;
            case 180:
                localRect.set(getWidth() - rect.right, getHeight() - rect.bottom,
                        getWidth() - rect.left, getHeight() - rect.top);
                break;
            case 270:
                localRect.set(getWidth() - rect.bottom, rect.left,
                        getWidth() - rect.top, rect.right);
                break;
        }
        return decodeRegion(localRect, sampleSize);
    }

    @MainThread
    private boolean getOrLoadTiles(@NonNull List<Tile> dst, @NonNull List<Tile> tileGrid,
                                   int sampleSize, @NonNull Rect displayRect) {
        boolean hitAll = true;
        for (final Tile tile : tileGrid) {
            if (Rect.intersects(tile.rect, displayRect)) {
                if (tile.isAvailable()) {
                    dst.add(tile);
                } else {
                    hitAll = false;
                    // Check the loading flag to prevent duplicated decode tasks.
                    if (!tile.loading) {
                        tile.loading = true;
                        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
                            final Bitmap bitmap = decodeRegionRotated(tile.rect, sampleSize);
                            mMainThreadExecutor.execute(() -> {
                                tile.loading = false;
                                // Check if we should notify the callback that the tile is loaded.
                                if (mLastRequestSampleSize == sampleSize &&
                                        Rect.intersects(mLastRequestRect, tile.rect)) {
                                    tile.bitmap = bitmap;
                                    mTilesHit.add(tile);
                                    mCallback.accept(Collections.unmodifiableList(mTilesHit));
                                } else {
                                    bitmap.recycle();
                                }
                            });
                        });
                    }
                }
            } else if (tile.isAvailable()) {
                tile.bitmap.recycle();
            }
        }
        return hitAll;
    }

    @MainThread
    public List<Tile> requestTiles(float scale, int viewWidth, int viewHeight,
                                   @NonNull Rect displayRect) {
        assertMainThread();
        final int sampleSize = calculateInSampleSize(scale);
        List<Tile> tileGrid = mSampleSizeToTileGrid.get(sampleSize);
        if (tileGrid == null) {
            tileGrid = createTileGrid(sampleSize, viewWidth, viewHeight);
            mSampleSizeToTileGrid.put(sampleSize, tileGrid);
        }

        mTilesHit.clear();
        mLastRequestRect.set(displayRect);
        mLastRequestSampleSize = sampleSize;
        final boolean hitAll = getOrLoadTiles(mTilesHit, tileGrid, sampleSize, displayRect);
        if (hitAll) {
            // Recycle tiles in other sampleSize.
            final Iterator<Map.Entry<Integer, List<Tile>>> iterator =
                    mSampleSizeToTileGrid.entrySet().iterator();
            while (iterator.hasNext()) {
                final Map.Entry<Integer, List<Tile>> entry = iterator.next();
                if (sampleSize != entry.getKey()) {
                    for (final Tile tile : entry.getValue()) {
                        if (tile.isAvailable()) {
                            tile.bitmap.recycle();
                        }
                    }
                    iterator.remove();
                }
            }
        } else if (sampleSize != 1) {
            // Load tiles in other sampleSize. However, don't load when sampleSize is 1,
            // as loading requested tiles at this time is already a heavy task.
            for (final Map.Entry<Integer, List<Tile>> entry : mSampleSizeToTileGrid.entrySet()) {
                if (entry.getKey() > sampleSize) {
                    getTilesHit(mTilesHit, entry.getValue(), displayRect);
                }
            }
        }
        return Collections.unmodifiableList(mTilesHit);
    }
}
