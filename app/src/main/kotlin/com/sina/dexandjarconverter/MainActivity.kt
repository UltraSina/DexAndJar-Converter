package com.sina.dexandjarconverter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sina.dexandjarconverter.ui.theme.ComposeEmptyActivityTheme
import com.googlecode.d2j.dex.Dex2jar
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// ── Architecting the Combinations ────────────────────────────────────────────

enum class ConversionType(val inputName: String, val outputName: String, val inputExt: String, val outputExt: String, val outputMime: String) {
    DEX_TO_JAR("DEX", "JAR", "dex", "jar", "application/java-archive"),
    JAR_TO_DEX("JAR", "DEX", "jar", "dex", "application/octet-stream"),
    DEX_TO_SMALI("DEX", "Smali (ZIP)", "dex", "zip", "application/zip"),
    SMALI_TO_DEX("Smali (ZIP)", "DEX", "zip", "dex", "application/octet-stream"),
    JAR_TO_SMALI("JAR", "Smali (ZIP)", "jar", "zip", "application/zip"),
    SMALI_TO_JAR("Smali (ZIP)", "JAR", "zip", "jar", "application/java-archive")
}

class CreateDocumentWithMime : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.first) 
            .putExtra(Intent.EXTRA_TITLE, input.second) 
    }
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (resultCode == Activity.RESULT_OK) intent?.data else null
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ComposeEmptyActivityTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ConverterScreen(modifier = Modifier.padding(innerPadding), context = this)
                }
            }
        }
    }
}

@Composable
fun ConverterScreen(modifier: Modifier = Modifier, context: Context) {
    val coroutineScope = rememberCoroutineScope()
    
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var activeExecutor by remember { mutableStateOf<ExecutorService?>(null) }
    
    var progressMessage by remember { mutableStateOf("") }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    
    var currentOperation by remember { mutableStateOf<ConversionType?>(null) }
    var pendingInputUri by remember { mutableStateOf<Uri?>(null) }
    var isCancelledRequested by remember { mutableStateOf(false) }

    // ── Universal Pipeline Launcher ──
    val createDocLauncher = rememberLauncherForActivityResult(CreateDocumentWithMime()) { outputUri ->
        val inputUri = pendingInputUri
        val op = currentOperation
        pendingInputUri = null; currentOperation = null

        if (outputUri != null && inputUri != null && op != null) {
            isCancelledRequested = false
            val executor = Executors.newSingleThreadExecutor()
            activeExecutor = executor
            activeJob = coroutineScope.launch {
                resultMessage = try {
                    processUniversalConversion(context, inputUri, outputUri, op, { progressMessage = it }) { inF, outF ->
                        when (op) {
                            ConversionType.DEX_TO_JAR -> coreDexToJar(inF, outF, executor) { progressMessage = it }
                            ConversionType.JAR_TO_DEX -> coreJarToDex(inF, outF, executor) { progressMessage = it }
                            ConversionType.DEX_TO_SMALI -> coreDexToSmali(inF, outF, executor) { progressMessage = it }
                            ConversionType.SMALI_TO_DEX -> coreSmaliToDex(inF, outF, executor) { progressMessage = it }
                            ConversionType.JAR_TO_SMALI -> coreJarToSmali(inF, outF, executor) { progressMessage = it }
                            ConversionType.SMALI_TO_JAR -> coreSmaliToJar(inF, outF, executor) { progressMessage = it }
                        }
                    }
                    "Success: Converted ${op.inputName} to ${op.outputName}!"
                } catch (e: Throwable) {
                    if (isCancelledRequested) {
                        "Conversion cancelled by user."
                    } else {
                        var cause = e
                        while (cause is InvocationTargetException && cause.targetException != null) cause = cause.targetException
                        "Failed: ${cause.message ?: cause.javaClass.simpleName}"
                    }
                } finally {
                    activeJob = null; activeExecutor = null
                }
            }
        }
    }

    val pickFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && currentOperation != null) {
            pendingInputUri = uri
            val op = currentOperation!!
            createDocLauncher.launch(Pair(op.outputMime, "converted_output.${op.outputExt}"))
        } else {
            currentOperation = null
        }
    }

    // ── UI Layout ──
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Dex & Jar Converter", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(32.dp))

            val btnMod = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp)

            ConversionType.entries.forEach { op ->
                Button(onClick = { currentOperation = op; pickFileLauncher.launch("*/*") }, modifier = btnMod) {
                    Text("Convert ${op.inputName} to ${op.outputName}")
                }
            }
        }

        if (activeJob != null) {
            Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
                Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(
                        modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator(modifier = Modifier.size(36.dp))
                            Spacer(Modifier.width(16.dp))
                            Text(progressMessage, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(24.dp))
                        OutlinedButton(
                            onClick = { 
                                isCancelledRequested = true
                                progressMessage = "Cancelling..."
                                activeExecutor?.shutdownNow()
                                activeJob?.cancel() 
                            }, 
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Cancel", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }

        resultMessage?.let { msg ->
            AlertDialog(
                onDismissRequest = { resultMessage = null },
                confirmButton = { TextButton(onClick = { resultMessage = null }) { Text("OK") } },
                title = { Text(if (msg.startsWith("Failed")) "Error" else "Done") },
                text = { Text(msg) }
            )
        }
    }
}

// ── Universal I/O Wrapper ────────────────────────────────────────────────────

suspend fun processUniversalConversion(
    context: Context, inputUri: Uri, outputUri: Uri, op: ConversionType, onProgress: (String) -> Unit,
    conversionBlock: suspend (inputFile: File, outputFile: File) -> Unit
) = withContext(Dispatchers.IO) {
    val ts = System.currentTimeMillis()
    val inputFile = File(context.cacheDir, "in_$ts.${op.inputExt}")
    val outputFile = File(context.cacheDir, "out_$ts.${op.outputExt}")
    
    try {
        onProgress("Reading input file...")
        uriToFile(context, inputUri, inputFile)
        ensureActive()

        conversionBlock(inputFile, outputFile)

        onProgress("Saving final file...")
        fileToUri(context, outputFile, outputUri)
    } finally {
        inputFile.delete()
        outputFile.delete()
    }
}

// ── Engine 1: DEX to JAR ─────────────────────────────────────────────────────
suspend fun coreDexToJar(inputDex: File, outputJar: File, executor: ExecutorService, onProgress: (String) -> Unit) = coroutineScope {
    val tempDir = File(inputDex.parentFile, "d2j_${System.currentTimeMillis()}")
    tempDir.mkdirs()
    try {
        val totalClasses = getDexClassCount(inputDex)
        val future = executor.submit { Dex2jar.from(inputDex).to(tempDir.toPath()) }
        
        while (!future.isDone) {
            ensureActive()
            val fileCount = tempDir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.count()
            if (totalClasses > 0) {
                val percent = minOf((fileCount * 100) / totalClasses, 100)
                onProgress("Translating DEX... $percent% ($fileCount/$totalClasses classes)")
            } else {
                onProgress("Translating DEX... $fileCount classes")
            }
            delay(250) 
        }

        try { future.get() } catch (e: ExecutionException) { throw e.cause ?: e } 

        val files = tempDir.walkTopDown().filter { it.isFile }.toList()
        val totalFiles = files.size
        if (totalFiles == 0) throw RuntimeException("No classes were generated. File may be invalid.")

        ZipOutputStream(FileOutputStream(outputJar)).use { zout ->
            files.forEachIndexed { index, file ->
                ensureActive() 
                if (index % 50 == 0 || index == totalFiles - 1) onProgress("Packaging JAR: ${((index + 1) * 100) / totalFiles}%")
                
                val entryName = tempDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                zout.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zout) }
                zout.closeEntry()
            }
        }
    } finally { tempDir.deleteRecursively() }
}

// ── Engine 2: JAR to DEX ─────────────────────────────────────────────────────
suspend fun coreJarToDex(inputJar: File, outputDex: File, executor: ExecutorService, onProgress: (String) -> Unit) = coroutineScope {
    val tempDir = File(inputJar.parentFile, "j2d_${System.currentTimeMillis()}")
    tempDir.mkdirs()
    
    // FIX: A private thread exclusively to hold D8 unblocks the Coroutine
    val d8MasterExecutor = Executors.newSingleThreadExecutor()
    
    try {
        val totalClasses = getJarClassCount(inputJar)
        val classInfo = if (totalClasses > 0) "$totalClasses classes" else "JAR"

        val trackingExecutor = TrackingExecutor(executor) { completed, _ ->
            if (completed % 10 == 0) onProgress("Compiling $classInfo...\n($completed ops finished)")
        }

        val command = com.android.tools.r8.D8Command.builder()
            .addProgramFiles(inputJar.toPath())
            .setOutput(tempDir.toPath(), com.android.tools.r8.OutputMode.DexIndexed)
            .setDisableDesugaring(true)
            .setMinApiLevel(28)
            .build()
            
        val future = d8MasterExecutor.submit {
            com.android.tools.r8.D8.run(command, trackingExecutor)
        }

        // Beautifully unblocked cancellation polling loop!
        while (!future.isDone) {
            ensureActive()
            delay(250)
        }

        try { future.get() } catch (e: ExecutionException) { throw e.cause ?: e }

        val classesDex = File(tempDir, "classes.dex")
        if (classesDex.exists()) {
            classesDex.copyTo(outputDex, overwrite = true)
        } else {
            val anyDex = tempDir.listFiles { _, name -> name.endsWith(".dex") }?.firstOrNull() ?: throw RuntimeException("Conversion finished but no DEX produced.")
            anyDex.copyTo(outputDex, overwrite = true)
        }
    } finally { 
        d8MasterExecutor.shutdownNow() // Violently terminate D8 on Cancel
        tempDir.deleteRecursively() 
    }
}

// ── Engine 3: DEX to SMALI ───────────────────────────────────────────────────
suspend fun coreDexToSmali(inputDex: File, outputZip: File, executor: ExecutorService, onProgress: (String) -> Unit) = coroutineScope {
    val tempDir = File(inputDex.parentFile, "d2s_${System.currentTimeMillis()}")
    tempDir.mkdirs()
    try {
        onProgress("Disassembling DEX to Smali...")
        val future = executor.submit {
            try {
                val mainClass = Class.forName("org.jf.baksmali.Main")
                val mainMethod = mainClass.getMethod("main", Array<String>::class.java)
                mainMethod.invoke(null, arrayOf("disassemble", inputDex.absolutePath, "-o", tempDir.absolutePath))
            } catch (e: ClassNotFoundException) { throw RuntimeException("Baksmali library missing in build.gradle.") }
        }

        while (!future.isDone) { ensureActive(); delay(250) }
        try { future.get() } catch (e: ExecutionException) { throw e.cause ?: e }

        val files = tempDir.walkTopDown().filter { it.isFile }.toList()
        val totalFiles = files.size
        if (totalFiles == 0) throw RuntimeException("Disassembly failed. No files generated.")

        ZipOutputStream(FileOutputStream(outputZip)).use { zout ->
            files.forEachIndexed { index, file ->
                ensureActive() 
                if (index % 50 == 0 || index == totalFiles - 1) onProgress("Zipping Smali: ${((index + 1) * 100) / totalFiles}%")
                
                val entryName = tempDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                zout.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zout) }
                zout.closeEntry()
            }
        }
    } finally { tempDir.deleteRecursively() }
}

// ── Engine 4: SMALI to DEX ───────────────────────────────────────────────────
suspend fun coreSmaliToDex(inputZip: File, outputDex: File, executor: ExecutorService, onProgress: (String) -> Unit) = coroutineScope {
    val tempDir = File(inputZip.parentFile, "s2d_${System.currentTimeMillis()}")
    tempDir.mkdirs()
    try {
        onProgress("Extracting Smali ZIP...")
        ZipInputStream(inputZip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                ensureActive() 
                val outFile = File(tempDir, entry.name)
                if (!outFile.canonicalPath.startsWith(tempDir.canonicalPath)) throw SecurityException("Zip Traversal detected")
                if (entry.isDirectory) { outFile.mkdirs() } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zis.copyTo(it) }
                }
                entry = zis.nextEntry
            }
        }

        onProgress("Assembling Smali to DEX...")
        val future = executor.submit {
            try {
                val mainClass = Class.forName("org.jf.smali.Main")
                val mainMethod = mainClass.getMethod("main", Array<String>::class.java)
                mainMethod.invoke(null, arrayOf("assemble", tempDir.absolutePath, "-o", outputDex.absolutePath))
            } catch (e: ClassNotFoundException) { throw RuntimeException("Smali library missing in build.gradle.") }
        }

        while (!future.isDone) { ensureActive(); delay(250) }
        try { future.get() } catch (e: ExecutionException) { throw e.cause ?: e }

        if (!outputDex.exists()) throw RuntimeException("Assembly failed. No DEX file was generated.")
    } finally { tempDir.deleteRecursively() }
}

// ── Mixed Engine 5: JAR to SMALI (Chained) ───────────────────────────────────
suspend fun coreJarToSmali(inputJar: File, outputZip: File, executor: ExecutorService, onProgress: (String) -> Unit) = coroutineScope {
    val tempDex = File(inputJar.parentFile, "chain_${System.currentTimeMillis()}.dex")
    try {
        coreJarToDex(inputJar, tempDex, executor) { msg -> onProgress("[Step 1/2]\n$msg") }
        ensureActive() 
        coreDexToSmali(tempDex, outputZip, executor) { msg -> onProgress("[Step 2/2]\n$msg") }
    } finally { tempDex.delete() }
}

// ── Mixed Engine 6: SMALI to JAR (Chained) ───────────────────────────────────
suspend fun coreSmaliToJar(inputZip: File, outputJar: File, executor: ExecutorService, onProgress: (String) -> Unit) = coroutineScope {
    val tempDex = File(inputZip.parentFile, "chain_${System.currentTimeMillis()}.dex")
    try {
        coreSmaliToDex(inputZip, tempDex, executor) { msg -> onProgress("[Step 1/2]\n$msg") }
        ensureActive()
        coreDexToJar(tempDex, outputJar, executor) { msg -> onProgress("[Step 2/2]\n$msg") }
    } finally { tempDex.delete() }
}

// ── Utilities ────────────────────────────────────────────────────────────────

private fun getDexClassCount(file: File): Int = try {
    file.inputStream().use { input ->
        input.skip(96)
        val bytes = ByteArray(4)
        if (input.read(bytes) == 4) ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int else 0
    }
} catch (e: Exception) { 0 }

private fun getJarClassCount(file: File): Int = try {
    var count = 0
    ZipInputStream(file.inputStream()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name.endsWith(".class")) count++
            entry = zis.nextEntry
        }
    }
    count
} catch (e: Exception) { 0 }

class TrackingExecutor(private val delegate: ExecutorService, private val onProgress: (completed: Int, total: Int) -> Unit) : ExecutorService by delegate {
    private val totalTasks = AtomicInteger(0); private val completedTasks = AtomicInteger(0)
    private fun update() = onProgress(completedTasks.get(), totalTasks.get())
    private fun wrap(command: Runnable): Runnable = Runnable { try { command.run() } finally { completedTasks.incrementAndGet(); update() } }
    private fun <T> wrap(task: Callable<T>): Callable<T> = Callable { try { task.call() } finally { completedTasks.incrementAndGet(); update() } }
    override fun execute(command: Runnable) { totalTasks.incrementAndGet(); update(); delegate.execute(wrap(command)) }
    override fun submit(task: Runnable): Future<*> { totalTasks.incrementAndGet(); update(); return delegate.submit(wrap(task)) }
    override fun <T> submit(task: Callable<T>): Future<T> { totalTasks.incrementAndGet(); update(); return delegate.submit(wrap(task)) }
    override fun <T> submit(task: Runnable, result: T): Future<T> { totalTasks.incrementAndGet(); update(); return delegate.submit(wrap(task), result) }
    override fun <T> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> { val wrapped = tasks.map { wrap(it) }; totalTasks.addAndGet(wrapped.size); update(); return delegate.invokeAll(wrapped) }
    override fun <T> invokeAll(tasks: MutableCollection<out Callable<T>>, timeout: Long, unit: TimeUnit): MutableList<Future<T>> { val wrapped = tasks.map { wrap(it) }; totalTasks.addAndGet(wrapped.size); update(); return delegate.invokeAll(wrapped, timeout, unit) }
}

private suspend fun uriToFile(context: Context, src: Uri, dst: File) = withContext(Dispatchers.IO) {
    (context.contentResolver.openInputStream(src) ?: throw IllegalArgumentException("Cannot open input file.")).use { it.copyTo(FileOutputStream(dst)) }
}
private suspend fun fileToUri(context: Context, src: File, dst: Uri) = withContext(Dispatchers.IO) {
    (context.contentResolver.openOutputStream(dst) ?: throw IllegalArgumentException("Cannot open output file.")).use { src.inputStream().copyTo(it) }
}
