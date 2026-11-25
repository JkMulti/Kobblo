package com.kobblo.gallery.file.explorer

import android.app.Application
import com.kobblo.gallery.file.explorer.data.core.FileSizes

/** Size labels are drawn from places with no context at hand, and any component can start the process. */
class KobbloApp : Application() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.kobblo.gallery.file.explorer.compat.AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // Before anything else and in every process: Smart Features' jobs and its GPU check look it up.
        com.kobblo.gallery.file.explorer.smart.SmartFeatures.install(this)
        // The PDF process draws pages and nothing else, and mustn't pick up unfinished file jobs.
        if (com.kobblo.gallery.file.explorer.viewer.pdf.isPdfProcess(this)) return
        // Nor must the GPU check's, which only tries running a model once, where a crash can't hurt.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && getProcessName().endsWith(":gpucheck")) return
        // Before any activity starts, so the first one opens locked when App Lock is on.
        com.kobblo.gallery.file.explorer.security.AppLockGate.install(this)
        com.kobblo.gallery.file.explorer.compat.AppLanguage.reconcile(this)
        FileSizes.init(this)
        com.kobblo.gallery.file.explorer.fileops.FileJobs.init(this)
        com.kobblo.gallery.file.explorer.smart.SmartFeatures.keepIndexing(this)
    }
}
