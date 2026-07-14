package com.example.eyeprotect.visiontool.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class MainViewModelTest {

    @Test
    fun `toggleMode adds assist mode to selection`() {
        val viewModel = MainViewModel()

        viewModel.toggleMode(AssistMode.BLUE)

        assertEquals(setOf(AssistMode.BLUE), viewModel.selectedModes.value)
    }

    @Test
    fun `setAlpha updates overlay alpha`() {
        val viewModel = MainViewModel()

        viewModel.setAlpha(0.8f)

        assertEquals(0.8f, viewModel.textureAlpha.value)
    }
}
