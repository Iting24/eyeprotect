package com.example.eyeprotect.visiontool.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class MainViewModelTest {

    @Test
    fun `setMode updates current assist mode`() {
        val viewModel = MainViewModel()

        viewModel.setMode(AssistMode.BLUE)

        assertEquals(AssistMode.BLUE, viewModel.currentMode.value)
    }

    @Test
    fun `setAlpha updates overlay alpha`() {
        val viewModel = MainViewModel()

        viewModel.setAlpha(0.8f)

        assertEquals(0.8f, viewModel.textureAlpha.value)
    }
}
