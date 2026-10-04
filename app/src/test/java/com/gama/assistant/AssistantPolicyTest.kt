package com.gama.assistant
import org.junit.Assert.*
import org.junit.Test
class AssistantPolicyTest {
 @Test fun backgroundWithoutUserGrantUsesNotification(){assertEquals(BackgroundLaunchPolicy.Route.NOTIFICATION,BackgroundLaunchPolicy.route(false,false,false))}
 @Test fun backgroundWithOverlayGrantCanOpenRequestedApp(){assertEquals(BackgroundLaunchPolicy.Route.ACTIVITY,BackgroundLaunchPolicy.route(false,true,false))}
 @Test fun defaultAssistantCanOpenRequestedAppWithoutOverlay(){assertEquals(BackgroundLaunchPolicy.Route.ASSISTANT,BackgroundLaunchPolicy.route(false,false,true))}
 @Test fun visibleAppDoesNotNeedBackgroundPermission(){assertEquals(BackgroundLaunchPolicy.Route.ACTIVITY,BackgroundLaunchPolicy.route(true,false,false))}
 @Test fun revokingOverlayImmediatelyReturnsToNotification(){assertEquals(BackgroundLaunchPolicy.Route.ACTIVITY,BackgroundLaunchPolicy.route(false,true,false));assertEquals(BackgroundLaunchPolicy.Route.NOTIFICATION,BackgroundLaunchPolicy.route(false,false,false))}
 @Test fun unlockedPhoneDoesNotRequireVoiceIdentity(){assertFalse(AccessPolicy.requiresUnlock(false,false))}
 @Test fun lockedPhoneAllowsSafeCommands(){assertFalse(AccessPolicy.requiresUnlock(true,true))}
 @Test fun lockedPhoneProtectsPrivateCommands(){assertTrue(AccessPolicy.requiresUnlock(true,false))}
 @Test fun pendingCommandWaitsForAndroidUnlock(){val p=PendingCommand();p.set("abrir whatsapp",1000);assertNull(p.take(true,2000));assertEquals("abrir whatsapp",p.take(false,3000))}
 @Test fun unlockCallbackAndUserPresentConsumeOnlyOnce(){val p=PendingCommand();p.set("minhas notas",1000);assertEquals("minhas notas",p.take(false,2000));assertNull(p.take(false,2100))}
 @Test fun oldRequestsExpire(){val p=PendingCommand();p.set("abrir camera",1000);assertNull(p.take(false,91001))}
 @Test fun cancelledRequestsDoNotResume(){val p=PendingCommand();p.set("minhas notas",1000);p.clear();assertNull(p.take(false,2000))}
 @Test fun newerRequestReplacesOlder(){val p=PendingCommand();p.set("abrir camera",1000);p.set("abrir whatsapp",2000);assertEquals("abrir whatsapp",p.take(false,3000))}
 @Test fun spokenTimerUsesMinutes(){assertEquals(300,DeviceExtras.timer("timer de cinco minutos"));assertEquals(21,DeviceExtras.timer("temporizador por vinte e um segundos"))}
 @Test fun timerRejectsZeroOverflowAndExcessDuration(){assertNull(DeviceExtras.timer("timer de 0 segundos"));assertNull(DeviceExtras.timer("timer de 9999999999999999 horas"));assertNull(DeviceExtras.timer("timer de 25 horas"));assertEquals(86400,DeviceExtras.timer("timer de 24 horas"))}
 @Test fun spokenAlarmAndNumericAlarm(){assertEquals(7 to 30,DeviceExtras.alarm("alarme para sete e meia"));assertEquals(23 to 59,DeviceExtras.alarm("alarme as 23:59"))}
 @Test fun invalidAlarmIsNotScheduled(){assertNull(DeviceExtras.alarm("alarme para 24:00"));assertNull(DeviceExtras.alarm("alarme para 7:61"));assertNull(DeviceExtras.alarm("alarme para 7:30:20"))}
}
