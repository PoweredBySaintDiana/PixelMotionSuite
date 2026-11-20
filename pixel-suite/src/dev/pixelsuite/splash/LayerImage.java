package dev.pixelsuite.splash;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Picture;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

final class LayerImage {
    final int background;
    final int[] foreground;

    LayerImage(int background, int[] foreground) {
        this.background = background;
        this.foreground = foreground;
    }

    private static Drawable copy(Drawable original, Resources resources) {
        if (original == null || original.getConstantState() == null) return null;
        Drawable cloned = original.getConstantState().newDrawable(resources).mutate();
        if (cloned == original) return null;
        cloned.setState(original.getState().clone());
        cloned.setLevel(original.getLevel());
        cloned.setLayoutDirection(original.getLayoutDirection());
        cloned.setAlpha(original.getAlpha());
        if (original.getColorFilter() != null) cloned.setColorFilter(original.getColorFilter());
        return cloned;
    }

    private static int[] render(Drawable layer, int size, float inset) {
        Bitmap image = Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
        try {
            int margin = Math.round(inset * size);
            layer.setBounds(margin,margin,size-margin,size-margin);
            try { layer.draw(new Canvas(image)); }
            catch (IllegalArgumentException | IllegalStateException hardware) {
                Picture recorded=new Picture();
                Canvas canvas=recorded.beginRecording(size,size);
                try { layer.draw(canvas); } finally { recorded.endRecording(); }
                Bitmap rendered=Bitmap.createBitmap(recorded);
                try {
                    Bitmap readable=rendered.copy(Bitmap.Config.ARGB_8888,false);
                    if (readable==null) throw hardware;
                    image.recycle(); image=readable;
                } finally { if (rendered!=image) rendered.recycle(); }
            }
            int[] pixels = new int[size*size];
            image.getPixels(pixels,0,size,0,0,size,size);
            return pixels;
        } finally { image.recycle(); }
    }

    static LayerImage from(AdaptiveIconDrawable icon, Resources resources, float inset) {
        Drawable back = copy(icon.getBackground(),resources), front = copy(icon.getForeground(),resources);
        if (back == null || front == null) return null;
        int color = SplashPolicy.background(render(back,32,inset));
        if (color == 0) return null;
        int[] pixels = render(front,SplashPolicy.SIZE,inset);
        return SplashPolicy.visibleForeground(pixels) ? new LayerImage(color,pixels) : null;
    }
}
