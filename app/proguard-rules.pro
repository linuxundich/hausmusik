
# WorkManager creates input mergers and workers by reflection. Without these
# rules R8 drops OverwritingInputMerger's constructor, Glance's session worker
# never runs and the home screen widget stays on its loading spinner.
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# Glance creates the widget's button actions by reflection as well; without a
# constructor every tap on the widget fails with NoSuchMethodException.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
