package com.nexora.backend.domain

import java.util.UUID

data class Community(val id: UUID, val name: String) {
    init {
        require(name.isNotBlank() && name.length <= 100 && name == name.trim()) {
            "El nombre debe tener entre 1 y 100 caracteres"
        }
    }
}
