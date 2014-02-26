package com.tbuckley.route;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.koushikdutta.async.http.AsyncHttpClient;
import com.koushikdutta.async.http.socketio.Acknowledge;
import com.koushikdutta.async.http.socketio.ConnectCallback;
import com.koushikdutta.async.http.socketio.EventCallback;
import com.koushikdutta.async.http.socketio.SocketIOClient;
import com.koushikdutta.async.http.socketio.StringCallback;

import android.os.Bundle;
import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.support.v7.media.MediaRouteSelector;
import android.support.v7.media.MediaRouter;
import android.support.v7.media.MediaRouter.RouteInfo;
 
public class MainActivity extends Activity {
	private static final String TAG = MainActivity.class.getSimpleName();
	
	private Activity mActivity;
    private WebView mWebView;
    private SocketIOClient mSocketIOClient;
    
    private Map<String, RouteInfo> mVisibleChromecasts;
    
    private MediaRouter mMediaRouter;
    private MediaRouteSelector mMediaRouteSelector;
    private MediaRouter.Callback mMediaRouterCallback;
    private CastDevice mSelectedDevice;
    private GoogleApiClient mApiClient;
    private Cast.Listener mCastListener;
    private ConnectionCallbacks mConnectionCallbacks;
    private ConnectionFailedListener mConnectionFailedListener;
    private boolean mWaitingForReconnect;
 
    private class MyMediaRouterCallback extends MediaRouter.Callback {
    	@Override
    	public void onRouteAdded(MediaRouter router, RouteInfo info) {
    		long threadId = Thread.currentThread().getId();
    		Log.d(TAG, "onRouteAdded thread: " + threadId);
    		
    		Log.d(TAG, "onRouteAdded: " + info.getName());
    		mVisibleChromecasts.put(info.getId(), info);
    		updateChromecastList();
    		// mMediaRouter.selectRoute(info);
    	}
    	
    	@Override
    	public void onRouteRemoved(MediaRouter router, RouteInfo info) {
    		Log.d(TAG, "onRouteRemoved: " + info.getName());
    		mVisibleChromecasts.remove(info.getId());
    		updateChromecastList();
    	}
    	
        @Override
        public void onRouteSelected(MediaRouter router, RouteInfo info) {
            Log.d(TAG, "onRouteSelected");
            // Handle the user route selection.
            mSelectedDevice = CastDevice.getFromBundle(info.getExtras());
            launchReceiver();
        }
    }
    
    private void updateChromecastList() {
    	JSONObject obj = new JSONObject();
    	Iterator<Map.Entry<String,RouteInfo>> it = mVisibleChromecasts.entrySet().iterator();
    	while(it.hasNext()) {
    		Map.Entry<String,RouteInfo> pairs = it.next();
    		try {
				obj.put(pairs.getKey(), pairs.getValue().getName());
			} catch (JSONException e) {
				e.printStackTrace();
			}
    	}
    	if(mSocketIOClient != null) {
    		Log.d(TAG, "Sending chromecasts: " + obj.toString());
    		mSocketIOClient.emit("Chromecasts", new JSONArray().put(obj));
    	}
    }
    
    private void launchReceiver() {
        try {
            mCastListener = new Cast.Listener() {
                @Override
                public void onApplicationDisconnected(int errorCode) {
                    Log.d(TAG, "application has stopped; teardown()");
                }
            };
            // Connect to Google Play services
            mConnectionCallbacks = new ConnectionCallbacks();
            mConnectionFailedListener = new ConnectionFailedListener();
            Cast.CastOptions.Builder apiOptionsBuilder = Cast.CastOptions
                    .builder(mSelectedDevice, mCastListener);
            mApiClient = new GoogleApiClient.Builder(this)
                    .addApi(Cast.API, apiOptionsBuilder.build())
                    .addConnectionCallbacks(mConnectionCallbacks)
                    .addOnConnectionFailedListener(mConnectionFailedListener)
                    .build();

            mApiClient.connect();
        } catch (Exception e) {
            Log.e(TAG, "Failed launchReceiver", e);
        }
    }
    
    private class ConnectionCallbacks implements GoogleApiClient.ConnectionCallbacks {
    	@Override
    	public void onConnected(Bundle connectionHint) {
    		if (mWaitingForReconnect) {
    			mWaitingForReconnect = false;
    			Log.d(TAG, "reconnectChannels()");
    		} else {
    			try {
    				Cast.CastApi.launchApplication(mApiClient, "F1CEDD59", false)
    					.setResultCallback(
    						new ResultCallback<Cast.ApplicationConnectionResult>() {
    							@Override
    							public void onResult(Cast.ApplicationConnectionResult result) {
    								Status status = result.getStatus();
    								if (status.isSuccess()) {
    									ApplicationMetadata applicationMetadata = 
    											result.getApplicationMetadata();
    									String sessionId = result.getSessionId();
    									String applicationStatus = result.getApplicationStatus();
    									boolean wasLaunched = result.getWasLaunched();
    								} else {
    									Log.d(TAG, "teardown()");
    								}
    							}
    						});

    			} catch (Exception e) {
    				Log.e(TAG, "Failed to launch application", e);
    			}
    		}
    	}

    	@Override
    	public void onConnectionSuspended(int cause) {
    		mWaitingForReconnect = true;
    	}
    }

    private class ConnectionFailedListener implements GoogleApiClient.OnConnectionFailedListener {
    	@Override
    	public void onConnectionFailed(ConnectionResult result) {
    		Log.d(TAG, "Connection failed");
    		Log.d(TAG, "teardown()");
    	}
    }
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        mActivity = this;
        
        // Hide title bar & buttons
        getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);

        // Keep screen on 24/7
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        // Configure Cast device discovery
        mMediaRouter = MediaRouter.getInstance(getApplicationContext());
        mMediaRouteSelector = new MediaRouteSelector.Builder()
        		.addControlCategory(
        				CastMediaControlIntent.categoryForCast(getResources()
        						.getString(R.string.app_id))).build();
        mMediaRouterCallback = new MyMediaRouterCallback();
        
        mVisibleChromecasts = new HashMap<String, RouteInfo>();
        
        // Configure webview
        mWebView = new WebView(this);
        WebSettings webSettings = mWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        mWebView.loadUrl("http://10.1.1.65:8000/");
        mWebView.addJavascriptInterface(new WebAppInterface(this), "Android");
        // @TODO(tbuckley) : evaluate if setWebViewClient is necessary
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });
 
        this.setContentView(mWebView);
        
        long threadId = Thread.currentThread().getId();
		Log.d(TAG, "Main thread: " + threadId);
        
        SocketIOClient.connect(AsyncHttpClient.getDefaultInstance(), "http://10.1.1.65:8000", new ConnectCallback() {
        	@Override
        	public void onConnectCompleted(Exception ex, SocketIOClient client) {
        		if(ex != null) {
        			ex.printStackTrace();
        			return;
        		}
        		mSocketIOClient = client;
        		client.on("connect", new EventCallback() {
					@Override
					public void onEvent(JSONArray args, Acknowledge acknowledge) {
						String id = args.optString(0);
						if(id == null) {
							Log.d(TAG, "Connect: no argument given");
							return;
						}
						Log.d(TAG, "Connecting to: " + id);
						final RouteInfo info = mVisibleChromecasts.get(id);
						if(info == null) {
							Log.d(TAG, "Connect: route not registered");
							return;
						}
						Log.d(TAG, "Connecting to chromecast: " + info.getName());
						long threadId = Thread.currentThread().getId();
			    		Log.d(TAG, "Socket client thread: " + threadId);
			    		mActivity.runOnUiThread(new Runnable() {
							@Override
							public void run() {
								long threadId = Thread.currentThread().getId();
					    		Log.d(TAG, "Runnable thread: " + threadId);
								mMediaRouter.selectRoute(info);
							}
			    		});

					}
        		});
        		updateChromecastList();
        	}
        });
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Start media router discovery
        mMediaRouter.addCallback(mMediaRouteSelector, mMediaRouterCallback,
                MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
    }

    @Override
    protected void onPause() {
        if (isFinishing()) {
            // End media router discovery
            mMediaRouter.removeCallback(mMediaRouterCallback);
        }
        super.onPause();
    }
}
