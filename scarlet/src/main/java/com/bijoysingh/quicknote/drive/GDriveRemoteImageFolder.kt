package com.bijoysingh.quicknote.drive

import com.bijoysingh.quicknote.database.GDriveDataType
import com.bijoysingh.quicknote.database.GDriveUploadDataDao
import com.maubis.scarlet.base.config.ApplicationBase.Companion.noteImagesFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

data class ImageUUID(
    val noteUuid: String,
    val imageUuid: String) {

  fun name(): String = "$noteUuid::$imageUuid"

  override fun equals(other: Any?): Boolean {
    if (other !is ImageUUID) {
      return false
    }
    return other.imageUuid == imageUuid && other.noteUuid == noteUuid
  }

  override fun hashCode(): Int {
    return Objects.hash(noteUuid, imageUuid)
  }
}

fun toImageUUID(imageUuid: String): ImageUUID? {
  val components = imageUuid.split("::")
  if (components.size == 2) {
    val noteUuid = components[0]
    val imageId = components[1]
    return ImageUUID(noteUuid, imageId)
  }
  return null
}

class GDriveRemoteImageFolder(
    dataType: GDriveDataType,
    database: GDriveUploadDataDao,
    helper: GDriveServiceHelper,
    onPendingChange: () -> Unit) : GDriveRemoteFolderBase(dataType, database, helper, onPendingChange) {

  var networkOrAbsoluteFailure = AtomicBoolean(false)

  val contentLoading = AtomicBoolean(true)
  var contentFolderUid: String = INVALID_FILE_ID
  val contentFiles = emptyMap<ImageUUID, String>().toMutableMap()

  val contentPendingActions = emptySet<ImageUUID>().toMutableSet()
  val deletedPendingActions = emptySet<ImageUUID>().toMutableSet()

  fun initContentFolderId(fUid: String, onLoaded: () -> Unit) {
    contentFolderUid = fUid
    GlobalScope.launch(Dispatchers.IO) {
      helper.getFilesInFolder(contentFolderUid, GOOGLE_DRIVE_IMAGE_MIME_TYPE).addOnCompleteListener {
        networkOrAbsoluteFailure.set(it.result === null)

        val imageFiles = it.result?.files
        if (imageFiles !== null) {
          imageFiles.forEach { imageFile ->
            val components = toImageUUID(imageFile.name)
            if (components !== null) {
              contentFiles[components] = imageFile.id
              notifyDriveData(imageFile)
            }
          }
          contentLoading.set(false)
        }
        GlobalScope.launch { onLoaded() }
      }
    }
  }

  fun insert(id: ImageUUID) {
    val logInfo = "insert($id)"

    if (contentLoading.get()) {
      contentPendingActions.add(id)
      return
    }

    if (networkOrAbsoluteFailure.get()) {
      return
    }

    if (contentFiles.containsKey(id)) {
      GlobalScope.launch {
        database.getByUUID(dataType.name, id.name())?.apply {
          gDriveUpdateTimestamp = lastUpdateTimestamp
          gDriveStateDeleted = localStateDeleted
          save(database)
        }
        onPendingChange()
      }
      return
    }

    val gDriveUUID = id.name()
    val timestamp = database.getByUUID(dataType.name, gDriveUUID)?.lastUpdateTimestamp
        ?: getTrueCurrentTime()
    val imageFile = noteImagesFolder.getFile(id.noteUuid, id.imageUuid)
    helper.createFileWithData(contentFolderUid, gDriveUUID, imageFile, timestamp)
        .addOnCompleteListener {
          val file = it.result
          if (file !== null) {
            contentFiles[id] = file.id
            notifyDriveData(file.id, gDriveUUID, timestamp)
          }
        }
  }

  fun delete(id: ImageUUID) {
    val logInfo = "delete($id)"
    if (contentLoading.get()) {
      deletedPendingActions.add(id)
      return
    }

    if (networkOrAbsoluteFailure.get()) {
      return
    }

    if (!contentFiles.containsKey(id)) {
      GlobalScope.launch {
        val existing = database.getByUUID(dataType.name, id.name())
        if (existing !== null) {
          database.delete(existing)
        }
      }
      return
    }

    GlobalScope.launch {
      val fuid = contentFiles[id] ?: INVALID_FILE_ID
      val existing = database.getByUUID(dataType.name, id.name())
      val timestamp = existing?.lastUpdateTimestamp ?: getTrueCurrentTime()

      helper.removeFileOrFolder(fuid)
          .addOnCompleteListener {
            notifyDriveData(fuid, id.name(), timestamp, true)
            contentFiles.remove(id)
          }
    }
  }
}