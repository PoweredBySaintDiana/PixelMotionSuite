package dev.pixelambient.aod;

import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.os.Parcel;
import android.os.SystemClock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class AodControls {
    static final String MODULE = "dev.pixelmotion.a10.recents";
    static final String HOST = "com.android.systemui";
    static final String ACTION = MODULE + ".AOD_CONTROLS";
    static final String PERMISSION = MODULE + ".permission.AOD_CONTROL";
    public static final String READ = "read", WALLPAPER = "wallpaper", SHADE = "shade", GAIN = "gain";
    static final long WAIT = 2200L;

    private AodControls() { }

    public static Bundle request(Context context, String operation, boolean enabled) {
        final Bundle response = new Bundle();
        final CountDownLatch received = new CountDownLatch(1);
        ResultReceiver reply = new ResultReceiver(null) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (data != null) response.putAll(data);
                received.countDown();
            }
        };
        try {
            Intent intent = new Intent(ACTION).setPackage(HOST)
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra("op", operation).putExtra("on", enabled).putExtra("reply", portable(reply))
                    .putExtra("until", SystemClock.elapsedRealtime() + WAIT);
            BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setShareIdentityEnabled(true);
            context.sendBroadcast(intent, null, options.toBundle());
            if (received.await(WAIT, TimeUnit.MILLISECONDS)) return response;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            android.util.Log.w("PixelSuite", "AOD controls unavailable", error);
        }
        return new Bundle();
    }

    private static ResultReceiver portable(ResultReceiver reply) {
        Parcel parcel = Parcel.obtain();
        try {
            reply.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return ResultReceiver.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
    }

}
