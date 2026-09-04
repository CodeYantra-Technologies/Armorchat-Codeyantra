package com.codeyantra.armorchat.domain.repository

import com.codeyantra.armorchat.domain.model.Contact
import kotlinx.coroutines.flow.Flow

interface ContactRepository {
    fun observeContacts(): Flow<List<Contact>>
}
