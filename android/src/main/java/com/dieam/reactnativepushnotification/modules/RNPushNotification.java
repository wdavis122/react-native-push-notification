package com.dieam.reactnativepushnotification.modules;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;

import com.dieam.reactnativepushnotification.helpers.ApplicationBadgeHelper;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import android.util.Log;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.iid.InstanceIdResult;
import com.google.firebase.messaging.FirebaseMessaging;

public class RNPushNotification extends ReactContextBaseJavaModule implements ActivityEventListener {
    public static final String LOG_TAG = "RNPushNotification";// all logging should use this tag

    private RNPushNotificationHelper mRNPushNotificationHelper;
    private final Random mRandomNumberGenerator = new Random(System.currentTimeMillis());
    private RNPushNotificationJsDelivery mJsDelivery;

    public RNPushNotification(ReactApplicationContext reactContext) {
        super(reactContext);

        reactContext.addActivityEventListener(this);

        Application applicationContext = (Application) reactContext.getApplicationContext();

        // The @ReactNative methods use this
        mRNPushNotificationHelper = new RNPushNotificationHelper(applicationContext);
        // This is used to delivery callbacks to JS
        mJsDelivery = new RNPushNotificationJsDelivery(reactContext);

        mRNPushNotificationHelper.checkOrCreateDefaultChannel();

        registerNotificationsReceiveNotificationActions();
    }

    @Override
    public String getName() {
        return "RNPushNotification";
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();

        return constants;
    }

    private Bundle getBundleFromIntent(Intent intent) {
        Bundle bundle = null;
        if (intent.hasExtra("notification")) {
            bundle = intent.getBundleExtra("notification");
        } else if (intent.hasExtra("google.message_id")) {
            bundle = intent.getExtras();
        }
        return bundle;
    }

    @Override
    public void onNewIntent(Intent intent) {
        Bundle bundle = this.getBundleFromIntent(intent);
        if (bundle != null) {
            bundle.putBoolean("foreground", false);
            intent.putExtra("notification", bundle);
            mJsDelivery.notifyNotification(bundle);
        }
    }

    private void registerNotificationsReceiveNotificationActions() {
        IntentFilter intentFilter = new IntentFilter();
        
        for(int i = 0; i < 10; i++) {
            intentFilter.addAction(getReactApplicationContext().getPackageName() + ".ACTION_" + i);
        }
        
        final RNPushNotification self = this;

        getReactApplicationContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle bundle = intent.getBundleExtra("notification");

                // Dismiss the notification popup.
                NotificationManager manager = (NotificationManager) context.getSystemService(context.NOTIFICATION_SERVICE);
                int notificationID = Integer.parseInt(bundle.getString("id"));

                boolean autoCancel = bundle.getBoolean("autoCancel", true);

                if(autoCancel) {
                  if (bundle.containsKey("tag")) {
                      String tag = bundle.getString("tag");
                      manager.cancel(tag, notificationID);
                  } else {
                      manager.cancel(notificationID);
                  }
                }

                boolean invokeApp = bundle.getBoolean("invokeApp", true);

                // Notify the action.
                if(invokeApp) {
                  self.invokeApp(bundle);
                } else {
                  mJsDelivery.notifyNotificationAction(bundle);
                }
            }
        }, intentFilter);
    }

    private void invokeApp(Bundle bundle) {
        ReactContext reactContext = getReactApplicationContext();
        String packageName = reactContext.getPackageName();
        Intent launchIntent = reactContext.getPackageManager().getLaunchIntentForPackage(packageName);
        String className = launchIntent.getComponent().getClassName();

        try {
            Class<?> activityClass = Class.forName(className);
            Intent activityIntent = new Intent(reactContext, activityClass);

            if(bundle != null) {
              activityIntent.putExtra("notification", bundle);
            }

            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            reactContext.startActivity(activityIntent);
        } catch(Exception e) {
            Log.e(LOG_TAG, "Class not found", e);
            return;
        }
    }
    
    @ReactMethod
    public void invokeApp(ReadableMap data) {
        Bundle bundle = null;

        if (data != null) {
            bundle = Arguments.toBundle(data);
        }

        invokeApp(bundle);
    }

    @ReactMethod
    public void checkPermissions(Promise promise) {
        ReactContext reactContext = getReactApplicationContext();
        NotificationManagerCompat managerCompat = NotificationManagerCompat.from(reactContext);
        promise.resolve(managerCompat.areNotificationsEnabled());
    }

    @ReactMethod
    public void requestPermissions() {
      final RNPushNotificationJsDelivery fMjsDelivery = mJsDelivery;
      
      FirebaseInstanceId.getInstance().getInstanceId()
              .addOnCompleteListener(new OnCompleteListener<InstanceIdResult>() {
                  @Override
                  public void onComplete(@NonNull Task<InstanceIdResult> task) {
                      if (!task.isSuccessful()) {
                          Log.e(LOG_TAG, "exception", task.getException());
                          return;
                      }

                      WritableMap params = Arguments.createMap();
                      params.putString("deviceToken", task.getResult().getToken());
                      fMjsDelivery.sendEvent("remoteNotificationsRegistered", params);
                  }
              });
    }

    @ReactMethod
    public void subscribeToTopic(String topic) {
        FirebaseMessaging.getInstance().subscribeToTopic(topic);
    }
    
    @ReactMethod
    public void unsubscribeFromTopic(String topic) {
        FirebaseMessaging.getInstance().unsubscribeFromTopic(topic);
    }

    @ReactMethod
    public void presentLocalNotification(ReadableMap details) {
        Bundle bundle = Arguments.toBundle(details);
        // If notification ID is not provided by the user, generate one at random
        if (bundle.getString("id") == null) {
            bundle.putString("id", String.valueOf(mRandomNumberGenerator.nextInt()));
        }
        mRNPushNotificationHelper.sendToNotificationCentre(bundle);
    }

    @ReactMethod
    public void scheduleLocalNotification(ReadableMap details) {
        Bundle bundle = Arguments.toBundle(details);
        // If notification ID is not provided by the user, generate one at random
        if (bundle.getString("id") == null) {
            bundle.putString("id", String.valueOf(mRandomNumberGenerator.nextInt()));
        }
        mRNPushNotificationHelper.sendNotificationScheduled(bundle);
    }

    @ReactMethod
    public void getInitialNotification(Promise promise) {
        WritableMap params = Arguments.createMap();
        Activity activity = getCurrentActivity();
        if (activity != null) {
            Bundle bundle = this.getBundleFromIntent(activity.getIntent());
            if (bundle != null) {
                bundle.putBoolean("foreground", false);
                String bundleString = mJsDelivery.convertJSON(bundle);
                params.putString("dataJSON", bundleString);
            }
        }
        promise.resolve(params);
    }

    @ReactMethod
    public void setApplicationIconBadgeNumber(int number) {
        ApplicationBadgeHelper.INSTANCE.setApplicationIconBadgeNumber(getReactApplicationContext(), number);
    }

    // removed @Override temporarily just to get it working on different versions of RN
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        onActivityResult(requestCode, resultCode, data);
    }

    // removed @Override temporarily just to get it working on different versions of RN
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        // Ignored, required to implement ActivityEventListener for RN 0.33
    }

    @ReactMethod
    /**
     * Cancels all scheduled local notifications, and removes all entries from the notification
     * centre.
     *
     */
    public void cancelAllLocalNotifications() {
        mRNPushNotificationHelper.cancelAllScheduledNotifications();
        mRNPushNotificationHelper.clearNotifications();
    }

    @ReactMethod
    /**
     * Cancel scheduled notifications, and removes notifications from the notification centre.
     *
     */
    public void cancelLocalNotifications(ReadableMap userInfo) {
        mRNPushNotificationHelper.cancelScheduledNotification(userInfo);
    }

    @ReactMethod
    /**
     * Clear notification from the notification centre.
     */
    public void clearLocalNotification(String tag, int notificationID) {
        mRNPushNotificationHelper.clearNotification(tag, notificationID);
    }

    @ReactMethod
    /**
     * Clears all notifications from the notification center
     *
     */
    public void removeAllDeliveredNotifications() {
      mRNPushNotificationHelper.clearNotifications();
    }

    @ReactMethod
    /**
     * Returns a list of all notifications currently in the Notification Center
     */
    public void getDeliveredNotifications(Callback callback) {
      callback.invoke(mRNPushNotificationHelper.getDeliveredNotifications());
    }

    @ReactMethod
    /**
     * Removes notifications from the Notification Center, whose id matches
     * an element in the provided array
     */
    public void removeDeliveredNotifications(ReadableArray identifiers) {
      mRNPushNotificationHelper.clearDeliveredNotifications(identifiers);
    }

    @ReactMethod
    /**
     * Unregister for all remote notifications received
     */
    public void abandonPermissions() {
      new Thread(new Runnable() {
          @Override
          public void run() {
              try {
                  FirebaseInstanceId.getInstance().deleteInstanceId();
                  Log.i(LOG_TAG, "InstanceID deleted");
              } catch (IOException e) {
                  Log.e(LOG_TAG, "exception", e);
              }
          }
      }).start();
    }

    @ReactMethod
    /**
     * List all channels id
     */
    public void getChannels(Callback callback) {
      WritableArray array = Arguments.fromList(mRNPushNotificationHelper.listChannels());
      
      if(callback != null) {
        callback.invoke(array);
      }
    }

    @ReactMethod
    /**
     * Check if channel exists with a given id
     */
    public void channelExists(String channel_id, Callback callback) {
      boolean exists = mRNPushNotificationHelper.channelExists(channel_id);

      if(callback != null) {
        callback.invoke(exists);
      }
    }

    @ReactMethod
    /**
     * Delete channel with a given id
     */
    public void deleteChannel(String channel_id) {
      mRNPushNotificationHelper.deleteChannel(channel_id);
    }
}
