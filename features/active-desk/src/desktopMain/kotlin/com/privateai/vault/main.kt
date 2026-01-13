package com.privateai.vault

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.privateai.vault.features.activedesk.ActiveDeskScreen
import com.privateai.vault.features.activedesk.ActiveDeskViewModel
import com.privateai.vault.inference.DesktopInferenceEngine
import com.privateai.vault.inference.ModelParams
import com.privateai.vault.vectorstore.SqliteVectorStore
import com.privateai.vault.vectorstore.createVectorStoreDriver
import com.privateai.vault.vectorstore.RagDocumentIngestor
import com.privateai.vault.vectorstore.RagContextualRetriever
import com.privateai.vault.vectorstore.RegexPrivacyRedactor
import kotlinx.coroutines.runBlocking
import java.io.File

fun main() = application {
    val possiblePaths = listOf(
        File("models/model.gguf"),
        File("../../models/model.gguf"),
        File("src/desktopMain/resources/model.gguf")
    )

    val modelFile = possiblePaths.find { it.exists() }
    val modelPath = modelFile?.absolutePath ?: "models/model.gguf"

    println("🔍 Looking for model...")
    if (modelFile != null) {
        println("✅ Found model at: ${modelFile.absolutePath}")
    } else {
        println("❌ Model NOT found. Checked paths: ${possiblePaths.map { it.absolutePath }}")
        println("   Current Working Directory: ${File(".").absolutePath}")
    }

    val dbPath = "build/private-ai.db"

    val inferenceEngine = DesktopInferenceEngine()
    
    runBlocking {
        if (!inferenceEngine.isModelLoaded() && File(modelPath).exists()) {
            println("Loading model from: $modelPath")
            try {
                inferenceEngine.loadModel(modelPath, ModelParams(embedding = true)).let { loaded ->
                    if (loaded) {
                        println("✅ Model loaded successfully in main.kt")
                    } else {
                        println("❌ Model failed to load in main.kt")
                    }
                }
            } catch (e: Exception) {
                println("Error loading model: ${e.message}")
            }
        }
    }

    val driver = createVectorStoreDriver(dbPath, "my-secret-passphrase")
    val vectorStore = SqliteVectorStore(
        driver = driver, 
        redactor = RegexPrivacyRedactor(),
        embeddingDimension = 3072 // Matches Llama 3.2 3B
    )
    
    // Initialize DB (load extensions)
    runBlocking { 
        vectorStore.initialize(requireVectorExtension = false) 
    }

    // FIX 3: Initialize the missing Retriever
    val retriever = RagContextualRetriever(
        vectorStore = vectorStore, 
        inferenceEngine = inferenceEngine
    )

    val ingestor = RagDocumentIngestor(vectorStore, inferenceEngine, null)

    // 3. Create ViewModel
    // FIX 4: Pass all 4 required arguments in the correct order
    val viewModel = ActiveDeskViewModel(
        inferenceEngine = inferenceEngine,
        vectorStore = vectorStore,
        contextualRetriever = retriever,
        documentIngestor = ingestor
    )

    // 4. Launch Window
    Window(onCloseRequest = ::exitApplication, title = "Private AI - Active Desk") {
        ActiveDeskScreen(viewModel)
    }
}