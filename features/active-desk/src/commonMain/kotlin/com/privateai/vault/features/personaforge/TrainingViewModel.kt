package com.privateai.vault.features.personaforge

import com.privateai.vault.inference.InferenceEngine
import com.privateai.vault.vectorstore.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import java.io.File

/**
 * Persona Forge - Stage 1: Training ViewModel
 *
 * Orchestrates the "Soul Baking" pipeline:
 * 1. Read source document
 * 2. Chunk into processable pieces
 * 3. Use DataExpander to generate synthetic Q&A pairs
 * 4. Export to training format
 * 5. (Future) Trigger LoRA training
 */
class TrainingViewModel(
    private val inferenceEngine: InferenceEngine,
    private val dataExpander: DataExpander,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val _state = MutableStateFlow(TrainingState())
    val state: StateFlow<TrainingState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<TrainingEffect>()
    val effects: SharedFlow<TrainingEffect> = _effects.asSharedFlow()

    private var bakingJob: Job? = null

    // Configuration
    private val questionsPerChunk = 5
    private val maxChunkSize = 1000 // characters
    private val outputDirectory = "training_data"

    /**
     * Handle UI events.
     */
    fun onEvent(event: TrainingEvent) {
        when (event) {
            is TrainingEvent.FileDropped -> handleFileDrop(event.path)
            is TrainingEvent.FileSelected -> handleFileDrop(event.path)
            is TrainingEvent.PersonaChanged -> selectPersona(event.persona)
            is TrainingEvent.CustomPersonaSet -> setCustomPersona(event.name, event.style)
            is TrainingEvent.StartBaking -> startBaking()
            is TrainingEvent.Cancel -> cancelBaking()
            is TrainingEvent.Reset -> reset()
            is TrainingEvent.ClearError -> clearError()
            is TrainingEvent.ExportData -> exportData(event.format)
            is TrainingEvent.OpenOutputFolder -> openOutputFolder()
        }
    }

    /**
     * Handle file drop/selection.
     */
    private fun handleFileDrop(path: String) {
        scope.launch {
            log(LogEntry.info("File received", path))

            val file = File(path)
            if (!file.exists()) {
                log(LogEntry.error("File not found", path))
                _state.update { it.copy(error = "File not found: $path") }
                return@launch
            }

            val extension = file.extension.lowercase()
            val fileType = SourceFileType.fromExtension(extension)

            if (fileType == SourceFileType.UNKNOWN) {
                log(LogEntry.warning("Unsupported file type", ".$extension"))
                _state.update { it.copy(error = "Unsupported file type: .$extension") }
                return@launch
            }

            _state.update {
                it.copy(
                    phase = TrainingPhase.ReadingFile(file.name),
                    sourceFile = SourceFile(
                        path = path,
                        name = file.name,
                        size = file.length(),
                        type = fileType
                    ),
                    error = null
                )
            }

            // Read file content
            try {
                val content = when (fileType) {
                    SourceFileType.PDF -> {
                        log(LogEntry.info("PDF extraction not yet implemented", "Using placeholder"))
                        // TODO: Integrate PDF extraction
                        "[PDF content would be extracted here]"
                    }
                    else -> file.readText()
                }

                // Chunk the content
                val chunks = chunkText(content, maxChunkSize)
                val totalQuestions = chunks.size * questionsPerChunk

                log(LogEntry.success("File loaded", "${content.length} chars, ${chunks.size} chunks"))

                _state.update { state ->
                    state.copy(
                        phase = TrainingPhase.Idle,
                        sourceFile = state.sourceFile?.copy(
                            content = content,
                            chunkCount = chunks.size
                        ),
                        progress = state.progress.copy(
                            chunksTotal = chunks.size,
                            questionsTotal = totalQuestions
                        )
                    )
                }

                _effects.emit(TrainingEffect.ShowToast("Ready to bake: ${file.name}"))

            } catch (e: Exception) {
                log(LogEntry.error("Failed to read file", e.message ?: "Unknown error"))
                _state.update {
                    it.copy(
                        phase = TrainingPhase.Error("Failed to read file: ${e.message}"),
                        error = e.message
                    )
                }
            }
        }
    }

    /**
     * Select a persona for training data generation.
     */
    private fun selectPersona(persona: PersonaOption) {
        log(LogEntry.info("Persona selected", persona.displayName))
        _state.update { it.copy(selectedPersona = persona) }
    }

    /**
     * Set a custom persona.
     */
    private fun setCustomPersona(name: String, style: String) {
        val customPersona = Persona.custom(name, style)
        log(LogEntry.info("Custom persona created", name))
        _state.update {
            it.copy(
                selectedPersona = PersonaOption.CUSTOM
            )
        }
    }

    /**
     * Start the "baking" process - generate synthetic training data.
     */
    private fun startBaking() {
        val sourceFile = _state.value.sourceFile
        if (sourceFile?.content == null) {
            log(LogEntry.error("No file loaded", "Drop a file first"))
            return
        }

        if (!inferenceEngine.isModelLoaded()) {
            log(LogEntry.error("No model loaded", "Load a base model first"))
            _state.update { it.copy(error = "No model loaded. Please load a base model first.") }
            return
        }

        bakingJob = scope.launch {
            try {
                _effects.emit(TrainingEffect.PlaySound(SoundType.START))

                val startTime = Clock.System.now()
                _state.update {
                    it.copy(
                        progress = it.progress.copy(startTime = startTime)
                    )
                }

                log(LogEntry.info("=".repeat(50)))
                log(LogEntry.info("PERSONA FORGE - SOUL BAKING INITIATED"))
                log(LogEntry.info("=".repeat(50)))
                log(LogEntry.info("Source", sourceFile.name))
                log(LogEntry.info("Persona", _state.value.selectedPersona.displayName))
                log(LogEntry.info("Model", inferenceEngine.getModelInfo()?.name ?: "Unknown"))

                // Chunk the content
                val chunks = chunkText(sourceFile.content, maxChunkSize)
                val allExamples = mutableListOf<TrainingExample>()

                log(LogEntry.info("Processing ${chunks.size} chunks..."))

                // Process each chunk
                for ((chunkIndex, chunk) in chunks.withIndex()) {
                    if (!isActive) break

                    log(LogEntry.debug("Chunk ${chunkIndex + 1}/${chunks.size}", "${chunk.length} chars"))

                    _state.update { state ->
                        state.copy(
                            phase = TrainingPhase.GeneratingSyntheticData(
                                currentQuestion = state.progress.questionsGenerated,
                                totalQuestions = state.progress.questionsTotal,
                                currentType = "Processing chunk ${chunkIndex + 1}"
                            )
                        )
                    }

                    // Expand this chunk into training examples
                    val result = dataExpander.expand(
                        sourceText = chunk,
                        persona = _state.value.selectedPersona.persona,
                        config = ExpansionConfig(
                            questionsPerChunk = questionsPerChunk,
                            temperature = 0.7f
                        ),
                        chunkId = "chunk_$chunkIndex"
                    )

                    // Log each generated example
                    for (example in result.examples) {
                        log(LogEntry.generation(
                            questionNum = allExamples.size + 1,
                            questionType = example.metadata?.questionType ?: "Q&A",
                            preview = example.instruction
                        ))
                    }

                    allExamples.addAll(result.examples)

                    // Update progress
                    val elapsed = Clock.System.now().toEpochMilliseconds() - startTime.toEpochMilliseconds()
                    _state.update { state ->
                        state.copy(
                            generatedExamples = allExamples.toList(),
                            progress = state.progress.copy(
                                questionsGenerated = allExamples.size,
                                tokensGenerated = state.progress.tokensGenerated + result.stats.tokensGenerated,
                                chunksProcessed = chunkIndex + 1,
                                elapsedMs = elapsed
                            )
                        )
                    }

                    _effects.emit(TrainingEffect.ScrollToBottom)

                    // Small delay to show progress
                    delay(100)
                }

                if (!isActive) {
                    log(LogEntry.warning("Baking cancelled by user"))
                    return@launch
                }

                // Export the data
                log(LogEntry.info("-".repeat(50)))
                log(LogEntry.info("EXPORTING TRAINING DATA"))

                _state.update {
                    it.copy(phase = TrainingPhase.ExportingData("JSONL"))
                }

                val outputPath = exportTrainingData(allExamples, sourceFile.name)

                // Simulate training phase (placeholder for future Python integration)
                log(LogEntry.info("-".repeat(50)))
                log(LogEntry.info("TRAINING SIMULATION"))
                log(LogEntry.warning("Actual LoRA training requires Python backend"))
                log(LogEntry.info("Training data saved - ready for external training"))

                _state.update {
                    it.copy(
                        phase = TrainingPhase.Training(epoch = 1, totalEpochs = 3, loss = 2.5f)
                    )
                }
                delay(500)

                for (epoch in 1..3) {
                    if (!isActive) break
                    val loss = 2.5f - (epoch * 0.5f) + (Math.random() * 0.2f).toFloat()
                    log(LogEntry.info("Epoch $epoch/3", "loss: %.4f".format(loss)))
                    _state.update {
                        it.copy(
                            phase = TrainingPhase.Training(epoch = epoch, totalEpochs = 3, loss = loss)
                        )
                    }
                    delay(800)
                }

                // Complete!
                val totalTime = Clock.System.now().toEpochMilliseconds() - startTime.toEpochMilliseconds()
                val soulName = sourceFile.name.substringBeforeLast(".") + "_soul"

                log(LogEntry.info("=".repeat(50)))
                log(LogEntry.success("SOUL BAKING COMPLETE"))
                log(LogEntry.success("Soul Name", soulName))
                log(LogEntry.success("Examples Generated", allExamples.size.toString()))
                log(LogEntry.success("Training Time", "${totalTime / 1000}s"))
                log(LogEntry.success("Output", outputPath))
                log(LogEntry.info("=".repeat(50)))

                _state.update {
                    it.copy(
                        phase = TrainingPhase.Complete(
                            soulName = soulName,
                            examplesGenerated = allExamples.size,
                            trainingTimeMs = totalTime
                        ),
                        outputPath = outputPath
                    )
                }

                _effects.emit(TrainingEffect.PlaySound(SoundType.COMPLETE))
                _effects.emit(TrainingEffect.ShowToast("Soul baked: $soulName"))

            } catch (e: CancellationException) {
                log(LogEntry.warning("Baking cancelled"))
                _state.update { it.copy(phase = TrainingPhase.Idle) }
            } catch (e: Exception) {
                log(LogEntry.error("Baking failed", e.message ?: "Unknown error"))
                _state.update {
                    it.copy(
                        phase = TrainingPhase.Error(e.message ?: "Unknown error"),
                        error = e.message
                    )
                }
                _effects.emit(TrainingEffect.PlaySound(SoundType.ERROR))
            }
        }
    }

    /**
     * Cancel the current baking operation.
     */
    private fun cancelBaking() {
        bakingJob?.cancel()
        bakingJob = null
        log(LogEntry.warning("Operation cancelled by user"))
        _state.update { it.copy(phase = TrainingPhase.Idle) }
    }

    /**
     * Reset to initial state.
     */
    private fun reset() {
        bakingJob?.cancel()
        bakingJob = null
        _state.value = TrainingState()
        log(LogEntry.info("State reset"))
    }

    /**
     * Clear error state.
     */
    private fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Export generated data to file.
     */
    private fun exportData(format: String) {
        val examples = _state.value.generatedExamples
        if (examples.isEmpty()) {
            log(LogEntry.warning("No data to export"))
            return
        }

        scope.launch {
            try {
                val formatEnum = TrainingDataFormat.valueOf(format.uppercase())
                val exported = dataExpander.export(examples, formatEnum)

                val fileName = "training_data_${System.currentTimeMillis()}.${format.lowercase()}"
                val outputDir = File(outputDirectory)
                outputDir.mkdirs()
                val outputFile = File(outputDir, fileName)
                outputFile.writeText(exported)

                log(LogEntry.success("Data exported", outputFile.absolutePath))
                _effects.emit(TrainingEffect.FileSaved(outputFile.absolutePath))

            } catch (e: Exception) {
                log(LogEntry.error("Export failed", e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Open the output folder.
     */
    private fun openOutputFolder() {
        val path = _state.value.outputPath ?: outputDirectory
        scope.launch {
            _effects.emit(TrainingEffect.OpenFolder(File(path).parent ?: path))
        }
    }

    /**
     * Export training data to JSONL file.
     */
    private fun exportTrainingData(examples: List<TrainingExample>, sourceName: String): String {
        val outputDir = File(outputDirectory)
        outputDir.mkdirs()

        val timestamp = System.currentTimeMillis()
        val baseName = sourceName.substringBeforeLast(".")
        val fileName = "${baseName}_training_$timestamp.jsonl"
        val outputFile = File(outputDir, fileName)

        val jsonl = dataExpander.export(examples, TrainingDataFormat.JSONL)
        outputFile.writeText(jsonl)

        log(LogEntry.success("Exported ${examples.size} examples", outputFile.absolutePath))

        return outputFile.absolutePath
    }

    /**
     * Chunk text into processable pieces.
     */
    private fun chunkText(text: String, maxSize: Int): List<String> {
        val chunks = mutableListOf<String>()
        val paragraphs = text.split("\n\n")

        var currentChunk = StringBuilder()

        for (paragraph in paragraphs) {
            if (currentChunk.length + paragraph.length > maxSize && currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.toString().trim())
                currentChunk = StringBuilder()
            }
            currentChunk.append(paragraph).append("\n\n")
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }

        return chunks.filter { it.length > 50 } // Skip very small chunks
    }

    /**
     * Add a log entry.
     */
    private fun log(entry: LogEntry) {
        _state.update { state ->
            state.copy(logs = state.logs + entry)
        }
    }

    /**
     * Clean up resources.
     */
    fun dispose() {
        bakingJob?.cancel()
        scope.cancel()
    }
}
