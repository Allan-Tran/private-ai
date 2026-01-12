package com.privateai.vault.inference

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class ModelIntegrationTest {

    @Test
    fun `test model loads successfully`() = runTest {
        // 1. Define the path to your model
        // NOTE: This path is relative to the core/inference-engine folder when running tests
        val modelPath = "../../models/model.gguf" 
        val modelFile = File(modelPath)

        // 2. Fail instantly if file is missing
        println("Checking for model at: ${modelFile.absolutePath}")
        assertTrue(modelFile.exists(), "❌ Model file not found! Did you download it to the /models folder?")

        // 3. Initialize the Engine
        val engine = DesktopInferenceEngine()
        val params = ModelParams(
            contextSize = 512,
            gpuLayers = 0, // CPU for testing
            threads = 4
        )

        // 4. Attempt to load
        println("Attempting to load model...")
        val isLoaded = engine.loadModel(modelFile.absolutePath, params)
        
        // 5. Verify
        assertTrue(isLoaded, "❌ Engine failed to load the model file.")
        assertNotNull(engine.getModelInfo(), "Model info should not be null")
        
        println("✅ Model loaded successfully!")
        
        // Cleanup
        engine.unloadModel()
    }
}