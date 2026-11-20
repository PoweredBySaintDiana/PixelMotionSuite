package dev.pixelmotion.a10.recents;

import android.view.MotionEvent;
import com.android.quickstep.AbsSwipeUpHandler;
import com.android.quickstep.inputconsumers.OtherActivityInputConsumer;
import com.android.quickstep.views.RecentsView;
import de.robv.android.xposed.XposedBridge;

public class ShelfDragTest {
    static int checks;
    static void check(boolean value) { if (!value) throw new AssertionError("check " + checks); checks++; }
    static void move(OtherActivityInputConsumer input,float dx,float dy) throws Throwable {
        MotionEvent event=new MotionEvent(); event.x=dx; event.y=dy; XposedBridge.before("onMotionEvent",input,event);
    }
    public static void main(String[] args) throws Throwable {
        TaskShelf.install(ShelfDragTest.class.getClassLoader());
        for (int scenario=0;scenario<8;scenario++) {
            OtherActivityInputConsumer input=new OtherActivityInputConsumer();
            AbsSwipeUpHandler swipe=input.mInteractionHandler;
            RecentsView view=swipe.mRecentsView;
            TaskShelf.onProgress(swipe,view,.5f,false);
            RecentsView.ADJACENT_PAGE_HORIZONTAL_OFFSET.setValue(view,.2f);
            check(view.mAdjacentPageHorizontalOffset==.5f);
            float dx=-220,dy=-160;
            if(scenario==1) dx=220;
            if(scenario==2) dy=-15;
            if(scenario==3) dx=-15;
            if(scenario==4) swipe.mIsMotionPaused=true;
            if(scenario==5) view.next=1;
            if(scenario==6) view.running=1;
            if(scenario==7) view.res.config.orientation=2;
            move(input,dx,dy);
            check(view.mAdjacentPageHorizontalOffset==(scenario==0?.2f:.5f));
            RecentsView.ADJACENT_PAGE_HORIZONTAL_OFFSET.setValue(view,.3f);
            TaskShelf.onProgress(swipe,view,.25f,false);
            check(view.mAdjacentPageHorizontalOffset==(scenario==0?.3f:.75f));
            TaskShelf.reset(swipe);
            check(view.mAdjacentPageHorizontalOffset==.3f);
        }
        OtherActivityInputConsumer early=new OtherActivityInputConsumer();
        move(early,-40,-50);
        TaskShelf.onProgress(early.mInteractionHandler,early.mInteractionHandler.mRecentsView,.1f,false);
        check(early.mInteractionHandler.mRecentsView.mAdjacentPageHorizontalOffset==0f);
        check(!ShelfDrag.leftUp(-4,-5,10));check(!ShelfDrag.leftUp(Float.NaN,-90,10));
        System.out.println("PASS: "+checks+" shelf handoff assertions");
    }
}
