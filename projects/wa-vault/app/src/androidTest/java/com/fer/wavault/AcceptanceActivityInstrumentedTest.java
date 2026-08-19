package com.fer.wavault;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class AcceptanceActivityInstrumentedTest {
    @Test public void acceptanceLauncherOpensAndShowsTitle() {
        ActivityScenario<AcceptanceActivity> scenario=ActivityScenario.launch(AcceptanceActivity.class);
        try {
            scenario.onActivity(activity->{
                AtomicBoolean found=new AtomicBoolean(false);
                walk(activity.getWindow().getDecorView(),found);
                assertTrue("Expected WA Vault Test title",found.get());
            });
        } finally { scenario.close(); }
    }

    private static void walk(View view,AtomicBoolean found){
        if(found.get()||view==null)return;
        if(view instanceof TextView){CharSequence text=((TextView)view).getText();if(text!=null&&"WA Vault Test".contentEquals(text)){found.set(true);return;}}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)walk(group.getChildAt(i),found);}
    }
}
