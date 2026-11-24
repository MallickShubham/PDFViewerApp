package com.example.pdfviewerapp;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.annotation.SuppressLint;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows a PDF (received as a URI) inside the app using Android's PdfRenderer.
 */
public class PdfActivity extends AppCompatActivity {

    // ---- Zoom settings ----
    private static final float MIN_ZOOM = 1.0f;
    private static final float MAX_ZOOM = 2.0f;   // higher values use a lot of memory
    private static final float ZOOM_STEP = 0.25f;

    // Safety limit for the size of one rendered page bitmap (in pixels)
    private static final long MAX_BITMAP_PIXELS = 12_000_000L;

    // ---- Threading ----
    // PdfRenderer is NOT thread-safe: one background thread + a lock.
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object renderLock = new Object();
    private volatile boolean closed = false;

    // ---- PDF resources (guarded by renderLock) ----
    private ParcelFileDescriptor fileDescriptor;
    private PdfRenderer pdfRenderer;

    // ---- PDF info ----
    private volatile int pageCount;
    private int[] pageWidths;
    private int[] pageHeights;

    // ---- Views ----
    private HorizontalScrollView scrollContainer;
    private RecyclerView recyclerView;
    private LinearLayoutManager layoutManager;
    private ProgressBar progressBar;
    private TextView errorText;
    private TextView pageIndicator;
    private View bottomBar;
    private Button zoomInButton;
    private Button zoomOutButton;

    // ---- Zoom state ----
    private PageAdapter adapter;
    private ScaleGestureDetector scaleDetector;
    private float zoom = 1.0f;
    private int baseWidthPx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish()); // back arrow
        // Keep the toolbar and bottom bar clear of the status bar and the
// phone's navigation (back) bar
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
        scrollContainer = findViewById(R.id.scrollContainer);
        recyclerView = findViewById(R.id.pdfRecyclerView);
        progressBar = findViewById(R.id.progressBar);
        errorText = findViewById(R.id.errorText);
        pageIndicator = findViewById(R.id.pageIndicator);
        bottomBar = findViewById(R.id.bottomBar);
        zoomInButton = findViewById(R.id.zoomInButton);
        zoomOutButton = findViewById(R.id.zoomOutButton);

        layoutManager = new LinearLayoutManager(this); // vertical list
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                updatePageIndicator();
            }
        });

        zoomInButton.setOnClickListener(v -> setZoom(zoom + ZOOM_STEP));
        zoomOutButton.setOnClickListener(v -> setZoom(zoom - ZOOM_STEP));
        scaleDetector = new ScaleGestureDetector(this, new PinchListener());

        // The PDF's location was passed from MainActivity
        Uri uri = getIntent().getData();
        if (uri == null) {
            showError(getString(R.string.error_no_file));
            return;
        }
        openPdfInBackground(uri);
    }

    // ------------------------------------------------------------------
    // Opening the PDF
    // ------------------------------------------------------------------

    /** Opens the file and reads page sizes on a background thread. */
    private void openPdfInBackground(Uri uri) {
        progressBar.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            ParcelFileDescriptor pfd = null;
            PdfRenderer renderer = null;
            try {
                // ContentResolver turns the content:// URI into a file descriptor
                pfd = getContentResolver().openFileDescriptor(uri, "r");
                if (pfd == null) {
                    throw new IOException("Could not open file descriptor");
                }
                renderer = new PdfRenderer(pfd); // throws for invalid PDFs

                int count = renderer.getPageCount();
                if (count <= 0) {
                    throw new IOException("PDF has no pages");
                }

                // Remember page sizes so each row gets the right height before drawing
                int[] widths = new int[count];
                int[] heights = new int[count];
                for (int i = 0; i < count; i++) {
                    try (PdfRenderer.Page page = renderer.openPage(i)) {
                        widths[i] = Math.max(1, page.getWidth());
                        heights[i] = Math.max(1, page.getHeight());
                    }
                }

                synchronized (renderLock) {
                    if (closed) { // activity was destroyed while loading
                        closeQuietly(renderer, pfd);
                        return;
                    }
                    pdfRenderer = renderer;
                    fileDescriptor = pfd;
                }
                pageWidths = widths;
                pageHeights = heights;
                pageCount = count;

                mainHandler.post(this::onPdfLoaded);

            } catch (SecurityException e) {
                // PdfRenderer throws this for password-protected PDFs
                closeQuietly(renderer, pfd);
                mainHandler.post(() -> showError(getString(R.string.error_password)));
            } catch (Exception e) {
                closeQuietly(renderer, pfd);
                mainHandler.post(() -> showError(getString(R.string.error_open_failed)));
            }
        });
    }

    /** Runs on the main thread once the PDF is ready. */
    private void onPdfLoaded() {
        if (closed) return;

        progressBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.VISIBLE);

        baseWidthPx = scrollContainer.getWidth();
        if (baseWidthPx <= 0) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            baseWidthPx = dm.widthPixels;
        }

        adapter = new PageAdapter();
        recyclerView.setAdapter(adapter);
        applyZoom();
        updatePageIndicator();
    }

    private void showError(String message) {
        progressBar.setVisibility(View.GONE);
        scrollContainer.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------
    // Zoom
    // ------------------------------------------------------------------

    private void setZoom(float newZoom) {
        if (adapter == null) return;
        float clamped = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
        if (Math.abs(clamped - zoom) < 0.01f) return;
        zoom = clamped;
        applyZoom();
    }

    /** Makes the page list wider/narrower and redraws the pages. */
    private void applyZoom() {
        int targetWidth = Math.round(baseWidthPx * zoom);
        ViewGroup.LayoutParams lp = recyclerView.getLayoutParams();
        lp.width = targetWidth;
        recyclerView.setLayoutParams(lp);
        adapter.setTargetWidth(targetWidth);

        zoomInButton.setEnabled(zoom < MAX_ZOOM - 0.01f);
        zoomOutButton.setEnabled(zoom > MIN_ZOOM + 0.01f);
    }

    /** Pinch-to-zoom: the new zoom level is applied when the gesture ends. */
    private class PinchListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        private float pendingZoom = 1.0f;

        @Override
        public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
            pendingZoom = zoom;
            return true;
        }

        @Override
        public boolean onScale(@NonNull ScaleGestureDetector detector) {
            pendingZoom *= detector.getScaleFactor();
            return true;
        }

        @Override
        public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
            setZoom(pendingZoom);
        }
    }

    /** Lets the scale detector watch touches without blocking normal scrolling. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        scaleDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    // ------------------------------------------------------------------
    // Page indicator
    // ------------------------------------------------------------------

    private void updatePageIndicator() {
        if (pageCount == 0) return;
        int position = layoutManager.findFirstVisibleItemPosition();
        if (position < 0) position = 0;
        pageIndicator.setText(getString(R.string.page_indicator, position + 1, pageCount));
    }

    // ------------------------------------------------------------------
    // Rendering a page to a bitmap
    // ------------------------------------------------------------------

    private void renderPageAsync(PageViewHolder holder, int index, int width, int height) {
        executor.execute(() -> {
            // Skip if this row was recycled or zoom changed while waiting
            if (holder.boundPosition != index || holder.boundWidth != width) return;

            Bitmap bitmap = null;
            synchronized (renderLock) {
                if (closed || pdfRenderer == null) return;

                try (PdfRenderer.Page page = pdfRenderer.openPage(index)) {
                    int bw = width;
                    int bh = height;
                    long pixels = (long) bw * bh;
                    if (pixels > MAX_BITMAP_PIXELS) { // keep memory under control
                        float f = (float) Math.sqrt((double) MAX_BITMAP_PIXELS / pixels);
                        bw = Math.max(1, Math.round(bw * f));
                        bh = Math.max(1, Math.round(bh * f));
                    }
                    bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.WHITE); // PDF pages are transparent by default
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                } catch (Exception | OutOfMemoryError e) {
                    bitmap = null;
                }
            }

            final Bitmap result = bitmap;
            mainHandler.post(() -> {
                if (closed || result == null) return;
                if (holder.boundPosition == index && holder.boundWidth == width) {
                    holder.imageView.setImageBitmap(result);
                }
            });
        });
    }

    // ------------------------------------------------------------------
    // RecyclerView adapter + holder
    // ------------------------------------------------------------------

    /** Holds one page image. */
    private static class PageViewHolder extends RecyclerView.ViewHolder {
        final ImageView imageView;
        volatile int boundPosition = -1;
        volatile int boundWidth = 0;

        PageViewHolder(@NonNull ImageView itemView) {
            super(itemView);
            this.imageView = itemView;
        }
    }

    /** Creates one ImageView per PDF page and fills it lazily. */
    private class PageAdapter extends RecyclerView.Adapter<PageViewHolder> {
        private int targetWidth;

        void setTargetWidth(int width) {
            this.targetWidth = width;
            notifyDataSetChanged(); // every page must be re-rendered at the new size
        }

        @NonNull
        @Override
        public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImageView imageView = new ImageView(parent.getContext());
            imageView.setScaleType(ImageView.ScaleType.FIT_XY);
            imageView.setBackgroundColor(Color.WHITE);

            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int margin = Math.round(6 * getResources().getDisplayMetrics().density);
            lp.setMargins(0, margin, 0, margin); // gap between pages
            imageView.setLayoutParams(lp);
            return new PageViewHolder(imageView);
        }
        @SuppressLint("RecyclerView")
        @Override
        public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
            holder.boundPosition = position;
            holder.boundWidth = targetWidth;

            // Reserve the correct height so scrolling doesn't jump
            int height = Math.max(1, Math.round(
                    targetWidth * (float) pageHeights[position] / pageWidths[position]));
            ViewGroup.LayoutParams lp = holder.imageView.getLayoutParams();
            lp.height = height;
            holder.imageView.setLayoutParams(lp);

            holder.imageView.setImageBitmap(null);
            renderPageAsync(holder, position, targetWidth, height);
        }

        @Override
        public void onViewRecycled(@NonNull PageViewHolder holder) {
            holder.boundPosition = -1;              // cancels pending render for this row
            holder.imageView.setImageBitmap(null);  // release the bitmap
        }

        @Override
        public int getItemCount() {
            return pageCount;
        }
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closed = true;
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        recyclerView.setAdapter(null);

        synchronized (renderLock) {
            closeQuietly(pdfRenderer, fileDescriptor);
            pdfRenderer = null;
            fileDescriptor = null;
        }
    }

    /** Closes the renderer and file descriptor, ignoring errors. Either may be null. */
    private static void closeQuietly(PdfRenderer renderer, ParcelFileDescriptor descriptor) {
        if (renderer != null) {
            try {
                renderer.close();
            } catch (Exception ignored) {
            }
        }
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (IOException ignored) {
            }
        }
    }
}
