package com.codeyantra.armorchat.data.contact

import com.codeyantra.armorchat.core.database.dao.ContactDao
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.repository.ContactRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class ContactRepositoryImpl @Inject constructor(
    private val contactDao: ContactDao,
) : ContactRepository {
    override fun observeContacts(): Flow<List<Contact>> =
        contactDao.observeAll().map { rows ->
            rows.map { Contact(it.id, it.displayName, it.createdAtEpochMs) }
        }
}
