package com.tbuckley.route;

import android.app.Activity;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;

public class WebAppInterface {
	Activity mActivity;
	
	/** Instantiate the interface and set the context */
	WebAppInterface(Activity c) {
		mActivity = c;
	}
	
	/** Set the screen's brightness */
	@JavascriptInterface
	public void setBrightness(int brightness) {
		mActivity.runOnUiThread(new BrightnessTask(brightness));
	}
	
	class BrightnessTask implements Runnable {
		int _brightness;
		
		BrightnessTask(int brightness) {
			_brightness = brightness;
		}
		
		public void run() {
			Window w = mActivity.getWindow();
			WindowManager.LayoutParams lp = w.getAttributes();
			lp.screenBrightness = (float)_brightness / 100;
			w.setAttributes(lp);
		}
	}
}
