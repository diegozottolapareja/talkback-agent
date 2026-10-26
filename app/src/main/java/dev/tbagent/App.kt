package dev.tbagent

import android.app.Application
import android.content.Context
import dev.tbagent.core.InstructionInterpreter
import dev.tbagent.core.RuleBasedInterpreter
import dev.tbagent.data.A11yEventLog
import dev.tbagent.data.RunStore
import dev.tbagent.data.Settings
import dev.tbagent.data.UtteranceRepository
import dev.tbagent.orchestrator.Orchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}

/**
 * Process-wide singletons. The TTS engine, the accessibility service and the UI all run in this
 * app's single process, so they share these objects directly.
 */
object Graph {
    lateinit var app: Context
        private set
    lateinit var settings: Settings
        private set
    lateinit var utterances: UtteranceRepository
        private set
    lateinit var runStore: RunStore
        private set
    lateinit var orchestrator: Orchestrator
        private set
    val events = A11yEventLog()
    val interpreter: InstructionInterpreter = RuleBasedInterpreter()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        settings = Settings(app)
        utterances = UtteranceRepository(File(app.filesDir, "utterances.jsonl"))
        runStore = RunStore(File(app.filesDir, "runs"))
        orchestrator = Orchestrator(app)
    }
}
