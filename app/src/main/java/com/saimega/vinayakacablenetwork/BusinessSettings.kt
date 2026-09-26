package com.saimega.vinayakacablenetwork

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

data class NetworkPackage(
    val name: String = "",
    val monthlyRate: Double = 0.0
)

data class BusinessSettings(
    val name: String = "",
    val phone: String = "",
    val address: String = "",
    val receiptFooter: String = "",
    val packages: List<NetworkPackage> = emptyList()
)

class BusinessSettingsRepository {

    private val db = FirebaseFirestore.getInstance()
    private val settingsRef = db.collection("settings").document("business")

    suspend fun load(): BusinessSettings {
        val snapshot = settingsRef.get().await()
        if (!snapshot.exists()) return BusinessSettings()
        val packages = (snapshot.get("packages") as? List<*>)
            .orEmpty()
            .mapNotNull { item ->
                val values = item as? Map<*, *> ?: return@mapNotNull null
                val name = values["name"] as? String ?: return@mapNotNull null
                val rate = (values["monthlyRate"] as? Number)?.toDouble() ?: return@mapNotNull null
                NetworkPackage(name, rate)
            }
        return BusinessSettings(
            name = snapshot.getString("name") ?: BusinessSettings().name,
            phone = snapshot.getString("phone") ?: BusinessSettings().phone,
            address = snapshot.getString("address") ?: BusinessSettings().address,
            receiptFooter = snapshot.getString("receiptFooter") ?: BusinessSettings().receiptFooter,
            packages = packages
        )
    }

    suspend fun save(settings: BusinessSettings): Boolean {
        return try {
            settingsRef.set(
                mapOf(
                    "name" to settings.name.trim(),
                    "phone" to settings.phone.trim(),
                    "address" to settings.address.trim(),
                    "receiptFooter" to settings.receiptFooter.trim(),
                    "packages" to settings.packages.take(4).map {
                        mapOf("name" to it.name.trim(), "monthlyRate" to it.monthlyRate)
                    }
                ),
                SetOptions.merge()
            ).await()
            true
        } catch (e: Exception) {
            false
        }
    }
}