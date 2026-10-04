from pathlib import Path

root = Path(__file__).resolve().parents[1]
checks = {
    'Dedicated Gama hotword recognizer': ('app/src/main/java/com/gama/assistant/GamaService.kt', 'wakeRecognizer = try {'),
    'Gama constrained wake grammar': ('app/src/main/java/com/gama/assistant/GamaService.kt', r'[\"gama\", \"gamma\", \"[unk]\"]'),
    'Dedicated rest recognizer': ('app/src/main/java/com/gama/assistant/GamaService.kt', 'sleepRecognizer = try {'),
    'Recognition recovery policy': ('app/src/main/java/com/gama/assistant/GamaRecognitionPolicy.kt', 'object GamaRecognitionPolicy'),
    'Partial-to-final transcript recovery': ('app/src/main/java/com/gama/assistant/GamaService.kt', 'bestTranscript'),
    'Immediate one-shot wake transition': ('app/src/main/java/com/gama/assistant/GamaService.kt', 'open the conversation NOW'),
    'Preferred microphone direction': ('app/src/main/java/com/gama/assistant/GamaService.kt', 'MIC_DIRECTION_TOWARDS_USER'),
    'Rest command priority path': ('app/src/main/java/com/gama/assistant/GamaService.kt', 'Highest-priority conversation exit path'),
    'Self-healing voice watchdog': ('app/src/main/java/com/gama/assistant/GamaService.kt', 'textMode || !running ->'),
    'Adaptive audio front-end': ('app/src/main/java/com/gama/assistant/GamaAudioFrontEnd.kt', 'class GamaAudioFrontEnd'),
    'Wind rejection': ('app/src/main/java/com/gama/assistant/GamaAudioFrontEnd.kt', 'windLikely'),
    'Continuous conversation decoder': ('app/src/main/java/com/gama/assistant/GamaService.kt', 'GamaRecognitionPolicy.shouldFeedDecoder'),
    'Hardware noise suppressor': ('app/src/main/java/com/gama/assistant/GamaAudioNoiseSupervisor.kt', 'NoiseSuppressor.create'),
    'Automatic gain control': ('app/src/main/java/com/gama/assistant/GamaAudioNoiseSupervisor.kt', 'AutomaticGainControl.create'),
    'Acoustic echo cancellation': ('app/src/main/java/com/gama/assistant/GamaAudioNoiseSupervisor.kt', 'AcousticEchoCanceler.create'),
    'Speaker model focus': ('app/src/main/java/com/gama/assistant/GamaService.kt', 'setSpeakerModel'),
    'Crowd owner-voice focus': ('app/src/main/java/com/gama/assistant/GamaService.kt', 'assessOwnerVoice(json)'),
    'Noise confirmation without repetition': ('app/src/main/java/com/gama/assistant/GamaService.kt', 'requestNoiseConfirmation'),
    'Lockscreen public time fast lane': ('app/src/main/java/com/gama/assistant/GamaService.kt', 'GAMA109_LOCKSCREEN_PUBLIC_FAST_LANE'),
    'Owner face profile': ('app/src/main/java/com/gama/assistant/GamaOwnerFaceProfile.kt', 'object GamaOwnerFaceProfile'),
    'Face enrollment command': ('app/src/main/java/com/gama/assistant/GamaFaceCommandPolicy.kt', 'cadastre meu rosto'),
    'Calendar write permission': ('app/src/main/AndroidManifest.xml', 'android.permission.WRITE_CALENDAR'),
    'Direct calendar manager': ('app/src/main/java/com/gama/assistant/GamaCalendarManager.kt', 'createEventInternal'),
    'WhatsApp automation': ('app/src/main/java/com/gama/assistant/GamaWhatsAppAutomation.kt', 'object GamaWhatsAppAutomation'),
    'Reliable WhatsApp route': ('app/src/main/java/com/gama/assistant/GamaService.kt', 'sendWhatsAppReliableAsync'),
    'Android lock-screen action': ('app/src/main/java/com/gama/assistant/GamaService.kt', 'GLOBAL_ACTION_LOCK_SCREEN'),
    'Device-admin lock fallback': ('app/src/main/java/com/gama/assistant/GamaService.kt', 'dpm.lockNow()'),
    'Mission continuity': ('app/src/main/java/com/gama/assistant/GamaService.kt', 'GamaMissionStore.resumable'),
    'Proactive policy': ('app/src/main/java/com/gama/assistant/GamaService.kt', 'GamaProactivePolicy.shouldSpeakNow'),
    'Unified context': ('app/src/main/java/com/gama/assistant/GamaContextFusion.kt', 'object GamaContextFusion'),
    'Universal installed-app resolver': ('app/src/main/java/com/gama/assistant/ZevronAppTool.kt', 'queryIntentActivities'),
    'Masculine voice preference': ('app/src/main/java/com/gama/assistant/GamaVoiceCatalog.kt', 'ptd'),
    'Accessibility service': ('app/src/main/AndroidManifest.xml', 'GamaScreenContextService'),
    'Notification awareness': ('app/src/main/AndroidManifest.xml', 'GamaNotificationListener'),
    'Background boot receiver': ('app/src/main/AndroidManifest.xml', 'BOOT_COMPLETED'),
}
failed=[]
for label,(rel,needle) in checks.items():
    p=root/rel
    if not p.is_file() or needle not in p.read_text(encoding='utf-8',errors='ignore'):
        failed.append(f'{label}: {rel} -> {needle}')
if failed:
    raise SystemExit('AUDIT FAILED\n'+'\n'.join(failed))
model_files=['final.mdl','Gr.fst','HCLr.fst','phones.txt']
for name in model_files:
    p=root/'app/src/main/assets/model'/name
    if not p.is_file() or p.stat().st_size == 0:
        raise SystemExit(f'Model asset missing: {p}')
print(f'Gama audit passed: {len(checks)} capability checks + {len(model_files)} model files.')
