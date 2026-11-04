package org.telegram.ui.Components;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;

public class PrizmaPdfViewer extends Dialog {
    private static final float MAX_ZOOM = 4f;

    public static boolean open(Activity activity, File file, String name) {
        if (activity == null || file == null) return false;
        PdfRenderer renderer;
        ParcelFileDescriptor descriptor;
        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(descriptor);
        } catch (Throwable e) {
            return false;
        }
        if (renderer.getPageCount() == 0) {
            renderer.close();
            return false;
        }
        new PrizmaPdfViewer(activity, renderer, descriptor, name).show();
        return true;
    }

    private final Activity activity;
    private final PdfRenderer renderer;
    private final ParcelFileDescriptor descriptor;
    private final String name;
    private final DispatchQueue queue = new DispatchQueue("libre_pdf");
    private final int pageCount;
    private final float[] ratios;
    private final LruCache<Integer, Bitmap> pages = new LruCache<Integer, Bitmap>(64 * 1024) {
        @Override
        protected int sizeOf(Integer key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };
    private ZoomFrame frame;
    private RecyclerView list;
    private LinearLayoutManager layout;
    private TextView counter;
    private boolean closed;
    private int renderWidth;

    private PrizmaPdfViewer(Activity activity, PdfRenderer renderer, ParcelFileDescriptor descriptor, String name) {
        super(activity, R.style.TransparentDialog);
        this.activity = activity;
        this.renderer = renderer;
        this.descriptor = descriptor;
        this.name = name;
        pageCount = renderer.getPageCount();
        ratios = new float[pageCount];
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Context ctx = getContext();
        renderWidth = Math.min(1440, AndroidUtilities.displaySize.x * 3 / 2);

        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(0xff101114);

        list = new RecyclerView(ctx);
        layout = new LinearLayoutManager(ctx);
        list.setLayoutManager(layout);
        list.setItemAnimator(null);
        list.setClipToPadding(false);
        list.setPadding(0, AndroidUtilities.statusBarHeight + AndroidUtilities.dp(60), 0, AndroidUtilities.dp(20) + AndroidUtilities.navigationBarHeight);
        list.setAdapter(new Pages());
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                updateCounter();
            }
        });
        frame = new ZoomFrame(ctx);
        frame.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.addView(frame, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        FrameLayout bar = new FrameLayout(ctx);
        bar.setBackgroundColor(0xe6101114);
        bar.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
        ImageView back = new ImageView(ctx);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setColorFilter(0xfff2f1ee);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setBackground(Theme.createSelectorDrawable(0x22ffffff, 1, AndroidUtilities.dp(24)));
        back.setOnClickListener(v -> dismiss());
        bar.addView(back, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 6, 4, 0, 4));
        TextView title = new TextView(ctx);
        title.setText(name == null ? "Документ" : name);
        title.setTextColor(0xfff2f1ee);
        title.setTextSize(16);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        bar.addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 62, 0, 86, 0));
        counter = new TextView(ctx);
        counter.setTextColor(0x99f2f1ee);
        counter.setTextSize(13);
        counter.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
        bar.addView(counter, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
        root.addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        PrizmaGlass.fullBleed(getWindow());
        updateCounter();
        root.setAlpha(0f);
        root.setTranslationY(AndroidUtilities.dp(30));
        root.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
    }

    private void updateCounter() {
        int first = layout == null ? 0 : Math.max(0, layout.findFirstVisibleItemPosition());
        counter.setText((first + 1) + " / " + pageCount);
    }

    @Override
    public void dismiss() {
        if (closed) return;
        closed = true;
        queue.postRunnable(() -> {
            try {
                renderer.close();
                descriptor.close();
            } catch (Throwable ignore) {
            }
            queue.recycle();
        });
        AndroidUtilities.runOnUIThread(pages::evictAll);
        try {
            super.dismiss();
        } catch (Throwable ignore) {
        }
    }

    private void render(int index, ImageView target) {
        queue.postRunnable(() -> {
            if (closed) return;
            Bitmap bitmap = null;
            float ratio = 1.414f;
            try (PdfRenderer.Page page = renderer.openPage(index)) {
                ratio = page.getHeight() / (float) Math.max(1, page.getWidth());
                int h = (int) (renderWidth * ratio);
                bitmap = Bitmap.createBitmap(renderWidth, h, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                Matrix m = new Matrix();
                float s = renderWidth / (float) page.getWidth();
                m.setScale(s, s);
                page.render(bitmap, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            } catch (Throwable ignore) {
            }
            final Bitmap done = bitmap;
            final float r = ratio;
            AndroidUtilities.runOnUIThread(() -> {
                if (closed) return;
                ratios[index] = r;
                if (done != null) {
                    pages.put(index, done);
                    if (Integer.valueOf(index).equals(target.getTag())) {
                        applyHeight(target, r);
                        target.setImageBitmap(done);
                    }
                }
            });
        });
    }

    private void applyHeight(ImageView view, float ratio) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        int w = AndroidUtilities.displaySize.x - AndroidUtilities.dp(16);
        int h = (int) (w * ratio);
        if (lp != null && lp.height != h) {
            lp.height = h;
            view.setLayoutParams(lp);
        }
    }

    private final class Pages extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            ImageView page = new ImageView(parent.getContext());
            page.setBackgroundColor(Color.WHITE);
            page.setScaleType(ImageView.ScaleType.FIT_XY);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(AndroidUtilities.displaySize.x - AndroidUtilities.dp(16), AndroidUtilities.dp(400));
            lp.leftMargin = lp.rightMargin = AndroidUtilities.dp(8);
            lp.bottomMargin = AndroidUtilities.dp(10);
            page.setLayoutParams(lp);
            return new RecyclerView.ViewHolder(page) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ImageView page = (ImageView) holder.itemView;
            page.setTag(position);
            page.setImageBitmap(null);
            float ratio = ratios[position] > 0 ? ratios[position] : 1.414f;
            applyHeight(page, ratio);
            Bitmap ready = pages.get(position);
            if (ready != null) {
                page.setImageBitmap(ready);
            } else {
                render(position, page);
            }
        }

        @Override
        public int getItemCount() {
            return pageCount;
        }
    }

    private static final class ZoomFrame extends FrameLayout {
        private final ScaleGestureDetector scale;
        private final GestureDetector pan;
        private float zoom = 1f;
        private float focusX, focusY;
        private boolean scaling;

        ZoomFrame(Context c) {
            super(c);
            scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(ScaleGestureDetector d) {
                    scaling = true;
                    return true;
                }

                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    zoom = Math.max(1f, Math.min(MAX_ZOOM, zoom * d.getScaleFactor()));
                    focusX = d.getFocusX();
                    focusY = d.getFocusY();
                    apply();
                    return true;
                }

                @Override
                public void onScaleEnd(ScaleGestureDetector d) {
                    scaling = false;
                }
            });
            pan = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    zoom = zoom > 1.05f ? 1f : 2.2f;
                    focusX = e.getX();
                    focusY = e.getY();
                    View child = getChildAt(0);
                    child.setPivotX(focusX);
                    child.setPivotY(focusY);
                    child.animate().scaleX(zoom).scaleY(zoom).translationX(zoom == 1f ? 0f : child.getTranslationX())
                            .setDuration(240).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
                    return true;
                }

                @Override
                public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                    if (zoom <= 1.01f || scaling) return false;
                    View child = getChildAt(0);
                    float limit = getWidth() * (zoom - 1f) / 2f;
                    child.setTranslationX(Math.max(-limit, Math.min(limit, child.getTranslationX() - dx)));
                    return false;
                }
            });
        }

        private void apply() {
            View child = getChildAt(0);
            child.setPivotX(focusX);
            child.setPivotY(focusY);
            child.setScaleX(zoom);
            child.setScaleY(zoom);
            if (zoom <= 1.01f) child.setTranslationX(0f);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            scale.onTouchEvent(e);
            pan.onTouchEvent(e);
            if (e.getPointerCount() > 1 || scaling) {
                MotionEvent cancel = MotionEvent.obtain(e);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                return true;
            }
            return super.dispatchTouchEvent(e);
        }
    }
}
