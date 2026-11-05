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

  try {
    var voice = DialogFlow.startUserFlow.overload(
        'com.vlingo.core.facade.dialogflow.RecognitionMode',
        'com.vlingo.core.internal.dialogmanager.WakeUpContext',
        'com.vlingo.core.api.audio.IAudioStream');
    voice.implementation = function (mode, wakeContext, stream) {
      var handled = false;
      try {
        handled = Bridge.onVoice(stream);
      } catch (e) {
        log('onVoice failed: ' + e);
      }
      if (handled) {
        log('microphone turn taken over');
        return true;
      }
      return voice.call(this, mode, wakeContext, stream);
    };
    log('hooked DialogFlow.startUserFlow(RecognitionMode, WakeUpContext, IAudioStream)');
  } catch (e) {
    log('voice hook failed: ' + e);
  }

  try {
    var typed = DialogFlow.startUserFlow.overload('java.lang.String', 'java.lang.Object');
    typed.implementation = function (utterance, state) {
      var handled = false;
      try {
        handled = Bridge.onText(utterance);
      } catch (e) {
        log('onText failed: ' + e);
      }
      if (handled) {
        log('typed turn taken over');
        return true;
      }
      return typed.call(this, utterance, state);
    };
    log('hooked DialogFlow.startUserFlow(String, Object)');
  } catch (e) {
    log('text hook failed: ' + e);
  }
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
