/*
 * S Voice Gemini bridge for Frida 12.8.x (Android 5.x, no Xposed).
 *
 * Attach to the S Voice process and it loads com.svoice.gemini.Bridge from the
 * installed bridge APK, then takes over the microphone and typed-request flows.
 * The "Hi Galaxy" wake-word recorder is left to S Voice on purpose.
 *
 * Expected S Voice: com.vlingo.midas 11.8/11.9 (TouchWiz 5.x).
 */
'use strict';

var TAG = '[svoice-gemini]';
var BRIDGE_PACKAGE = 'com.svoice.gemini';
var CONVERSATION = 'com.vlingo.midas.gui.ConversationActivity';
var DIALOG_FLOW = 'com.vlingo.core.internal.dialogmanager.DialogFlow';

function log(message) {
  try {
    console.log(TAG + ' ' + message);
  } catch (e) {
  }
}

function textOf(value) {
  if (value === null || value === undefined) return null;
  if (typeof value === 'string') return value;
  try {
    if (typeof value.value === 'string') return value.value;
    return String(value.value);
  } catch (e) {
    return String(value);
  }
}

function loadBridge(application) {
  var info = application.getPackageManager().getApplicationInfo(BRIDGE_PACKAGE, 0);
  var apk = textOf(info.sourceDir);
  if (!apk) throw new Error('bridge APK path not found; is ' + BRIDGE_PACKAGE + ' installed?');
  log('loading bridge from ' + apk);

  // Parent is S Voice's own loader, so every S Voice class stays reachable through
  // delegation and the class factory can borrow this loader for the rest of the session.
  var optimized = application.getDir('svoice_gemini_dex', 0).getAbsolutePath();
  var DexClassLoader = Java.use('dalvik.system.DexClassLoader');
  var loader = DexClassLoader.$new(apk, optimized, null, application.getClassLoader());
  Java.classFactory.loader = loader;

  var Bridge = Java.use(BRIDGE_PACKAGE + '.Bridge');
  Bridge.init(application);
  return Bridge;
}

function isConversation(activity) {
  try {
    return activity.getClass().getName() === CONVERSATION;
  } catch (e) {
    return false;
  }
}

function adoptCurrentActivity(Bridge) {
  try {
    var ActivityThread = Java.use('android.app.ActivityThread');
    var Record = Java.use('android.app.ActivityThread$ActivityClientRecord');
    var thread = ActivityThread.currentActivityThread();
    if (thread === null) return;
    var records = thread.mActivities.value;
    var values = records.values().iterator();
    while (values.hasNext()) {
      var record = Java.cast(values.next(), Record);
      var activity = record.activity.value;
      if (activity !== null && isConversation(activity)) {
        Bridge.setActivity(activity);
        log('adopted the running ConversationActivity');
        return;
      }
    }
  } catch (e) {
    log('activity adoption skipped: ' + e);
  }
}

function hookConversationActivity(Bridge) {
  var ConversationActivity;
  try {
    ConversationActivity = Java.use(CONVERSATION);
  } catch (e) {
    log('ConversationActivity not available: ' + e);
    return;
  }

  try {
    ConversationActivity.onCreate.overload('android.os.Bundle').implementation = function (state) {
      if (isConversation(this)) Bridge.setActivity(this);
      return this.onCreate(state);
    };
    log('hooked ConversationActivity.onCreate');
  } catch (e) {
    log('onCreate hook failed: ' + e);
  }

  try {
    ConversationActivity.onResume.overload().implementation = function () {
      if (isConversation(this)) Bridge.setActivity(this);
      return this.onResume();
    };
    log('hooked ConversationActivity.onResume');
  } catch (e) {
    log('onResume hook failed: ' + e);
  }
}

function hookDialogFlow(Bridge) {
  var DialogFlow;
  try {
    DialogFlow = Java.use(DIALOG_FLOW);
  } catch (e) {
    log('DialogFlow not available: ' + e);
    return;
  }

  var overloads;
  try {
    overloads = DialogFlow.startUserFlow.overloads;
  } catch (e) {
    log('startUserFlow not available: ' + e);
    return;
  }

  // S Voice 10.x and 11.x do not share one signature set: the Android 4 builds have no
  // IAudioStream parameter on the microphone overload and differ on the typed one. Hook
  // whatever exists instead of naming a single signature.
  var hooked = 0;
  var streamOnly = null;
  for (var i = 0; i < overloads.length; i++) {
    var overload = overloads[i];
    var types = [];
    var voice = false;
    var text = false;
    for (var j = 0; j < overload.argumentTypes.length; j++) {
      var name = overload.argumentTypes[j].className;
      types.push(name);
      if (j === 0 && name === 'java.lang.String') text = true;
      if (name.indexOf('RecognitionMode') >= 0) voice = true;
    }
    // The stream-only overload is the "Hi Galaxy" path on 11.x; leave that to S Voice
    // unless it is the only voice entry this S Voice build has.
    if (!voice && !text) {
      if (types.length === 1 && types[0].indexOf('AudioStream') >= 0) {
        streamOnly = overload;
        log('found the stream-only startUserFlow(' + types[0] + ')');
      } else {
        log('skipping startUserFlow(' + types.join(', ') + ')');
      }
      continue;
    }
    if (overload.argumentTypes.length > 3) {
      log('skipping startUserFlow(' + types.join(', ') + ')');
      continue;
    }
    hookStartUserFlow(Bridge, overload, voice);
    hooked++;
    log('hooked startUserFlow(' + types.join(', ') + ') ' + (voice ? 'voice' : 'text'));
  }
  if (hooked === 0 && streamOnly !== null) {
    hookStartUserFlow(Bridge, streamOnly, true);
    hooked++;
    log('hooked the stream-only startUserFlow; it is the only voice entry here');
  }
  log('startUserFlow overloads hooked: ' + hooked);
}

function streamOf(args) {
  for (var i = 0; i < args.length; i++) {
    var value = args[i];
    if (value === null || value === undefined) continue;
    try {
      var name = value.$className || '';
      if (!name && value.getClass) name = value.getClass().getName();
      if (name.indexOf('AudioStream') >= 0) return value;
    } catch (e) {
    }
  }
  return null;
}

function hookStartUserFlow(Bridge, overload, voice) {
  var returnsBoolean = overload.returnType.className === 'boolean';
  overload.implementation = function (a, b, c) {
    var handled = false;
    try {
      if (voice) {
        handled = Bridge.onVoice(streamOf(arguments));
      } else if (typeof a === 'string') {
        handled = Bridge.onText(a);
      }
    } catch (e) {
      log('turn hook failed: ' + e);
    }
    if (handled) {
      log((voice ? 'microphone' : 'typed') + ' turn taken over');
      return returnsBoolean ? true : undefined;
    }
    var count = arguments.length;
    if (count === 0) return overload.call(this);
    if (count === 1) return overload.call(this, a);
    if (count === 2) return overload.call(this, a, b);
    return overload.call(this, a, b, c);
  };
}

Java.perform(function () {
  var ActivityThread = Java.use('android.app.ActivityThread');
  var Thread = Java.use('java.lang.Thread');
  var application = null;
  for (var attempt = 0; attempt < 600 && application === null; attempt++) {
    try {
      application = ActivityThread.currentApplication();
    } catch (e) {
      application = null;
    }
    if (application === null) Thread.sleep(100);
  }
  if (application === null) {
    log('no Application after 60s, giving up');
    return;
  }

  try {
    var info = application.getPackageManager().getPackageInfo('com.vlingo.midas', 0);
    log('S Voice ' + textOf(info.versionName) + ' (' + info.versionCode + ') on Android '
        + textOf(Java.use('android.os.Build$VERSION').RELEASE.value));
  } catch (e) {
    log('could not read the S Voice version: ' + e);
  }

  var Bridge;
  try {
    Bridge = loadBridge(application);
  } catch (e) {
    log('bridge load failed: ' + e);
    return;
  }

  hookConversationActivity(Bridge);
  hookDialogFlow(Bridge);
  adoptCurrentActivity(Bridge);
  try {
    log('ready; bridge enabled = ' + Bridge.isEnabled());
  } catch (e) {
    log('ready, but reading settings failed: ' + e);
  }
});
