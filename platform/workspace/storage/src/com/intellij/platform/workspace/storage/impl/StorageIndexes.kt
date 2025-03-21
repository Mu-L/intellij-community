// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.platform.workspace.storage.impl

import com.google.common.collect.HashBiMap
import com.intellij.platform.workspace.storage.*
import com.intellij.platform.workspace.storage.impl.external.AbstractExternalEntityMappingImpl
import com.intellij.platform.workspace.storage.impl.external.ImmutableExternalEntityMappingImpl
import com.intellij.platform.workspace.storage.impl.external.MutableExternalEntityMappingImpl
import com.intellij.platform.workspace.storage.impl.indices.*
import com.intellij.platform.workspace.storage.impl.indices.VirtualFileIndex.MutableVirtualFileIndex.Companion.VIRTUAL_FILE_INDEX_ENTITY_SOURCE_PROPERTY

internal sealed class AbstractStorageIndexes() {
  // List of IDs of entities that use this particular persistent id
  internal abstract val softLinks: AbstractMultimapStorageIndex
  internal abstract val virtualFileIndex: VirtualFileIndex
  internal abstract val entitySourceIndex: EntityStorageInternalIndex<EntitySource>
  internal abstract val symbolicIdIndex: SymbolicIdInternalIndex
  internal abstract val externalMappings: Map<ExternalMappingKey<*>, AbstractExternalEntityMappingImpl<*>>

  fun assertConsistency(storage: AbstractEntityStorage) {
    assertEntitySourceIndex(storage)

    assertSymbolicIdIndex(storage)

    // TODO Should we get this back?
//    assertSoftLinksIndex(storage)

    virtualFileIndex.assertConsistency()

    // Assert external mappings
    for ((_, mappings) in externalMappings) {
      mappings.index.forEach { id, _ ->
        assert(storage.entityDataById(id) != null) { "Missing entity by id: $id" }
      }
    }
  }

/*
  private fun assertSoftLinksIndex(storage: AbstractEntityStorage) {

    // XXX skipped size check

    storage.entitiesByType.entityFamilies.forEachIndexed { i, family ->
      if (family == null) return@forEachIndexed
      if (family.entities.firstOrNull { it != null } !is SoftLinkable) return@forEachIndexed
      var mutableId = createEntityId(0, i)
      family.entities.forEach { data ->
        if (data == null) return@forEach
        mutableId = mutableId.copy(arrayId = data.id)
        val expectedLinks = softLinks.getEntriesById(mutableId)
        if (data is ModuleEntityData) {
          assertModuleSoftLinks(data, expectedLinks)
        }
        else {
          val actualLinks = (data as SoftLinkable).getLinks()
          assert(expectedLinks.size == actualLinks.size) { "Different sizes: $expectedLinks, $actualLinks" }
          assert(expectedLinks.all { it in actualLinks }) { "Different sets: $expectedLinks, $actualLinks" }
        }
      }
    }
  }
*/

  // XXX: Hack to speed up module links assertion
/*
  private fun assertModuleSoftLinks(entityData: ModuleEntityData, expectedLinks: Set<PersistentEntityId<*>>) {
    val actualRefs = HashSet<Any>(entityData.dependencies.size)
    entityData.dependencies.forEach { dependency ->
      when (dependency) {
        is ModuleDependency -> {
          assert(dependency.module in expectedLinks)
          actualRefs += dependency.module
        }
        is LibraryDependency -> {
          assert(dependency.library in expectedLinks)
          actualRefs += dependency.library
        }
        else -> Unit
      }
    }
    assert(actualRefs.size == expectedLinks.size)
  }
*/

  private fun assertSymbolicIdIndex(storage: AbstractEntityStorage) {

    var expectedSize = 0
    storage.entitiesByType.entityFamilies.forEachIndexed { i, family ->
      if (family == null) return@forEachIndexed
      val entityData = family.entities.firstOrNull { it != null }?.createEntity(storage) as? WorkspaceEntityWithSymbolicId
      if (entityData?.symbolicId == null) return@forEachIndexed
      var mutableId = createEntityId(0, i)
      family.entities.forEach { data ->
        if (data == null) return@forEach
        mutableId = mutableId.copy(arrayId = data.id)
        val expectedSymbolicId = symbolicIdIndex.getEntryById(mutableId)
        val symbolicId = (data.createEntity(storage) as? WorkspaceEntityWithSymbolicId)?.symbolicId
        assert(expectedSymbolicId == symbolicId) { "Entity $data isn't found in persistent id index. SymbolicId: $symbolicId, Id: $mutableId. Expected entity source: $expectedSymbolicId" }
        expectedSize++
      }
    }

    assert(expectedSize == symbolicIdIndex.index.size) { "Incorrect size of symbolic id index. Expected: $expectedSize, actual: ${symbolicIdIndex.index.size}" }
  }

  private fun assertEntitySourceIndex(storage: AbstractEntityStorage) {

    var expectedSize = 0
    storage.entitiesByType.entityFamilies.forEachIndexed { i, family ->
      if (family == null) return@forEachIndexed
      // Optimization to skip useless conversion of classes
      var mutableId = createEntityId(0, i)
      family.entities.forEach { data ->
        if (data == null) return@forEach
        mutableId = mutableId.copy(arrayId = data.id)
        val expectedEntitySource = entitySourceIndex.getEntryById(mutableId)
        assert(expectedEntitySource == data.entitySource) { "Entity $data isn't found in entity source index. Entity source: ${data.entitySource}, Id: $mutableId. Expected entity source: $expectedEntitySource" }
        expectedSize++
      }
    }

    assert(expectedSize == entitySourceIndex.index.size) { "Incorrect size of entity source index. Expected: $expectedSize, actual: ${entitySourceIndex.index.size}" }
  }
}
internal class ImmutableStorageIndexes(
  // List of IDs of entities that use this particular persistent id
  override val softLinks: ImmutableMultimapStorageIndex,
  override val virtualFileIndex: VirtualFileIndex,
  override val entitySourceIndex: EntityStorageInternalIndex<EntitySource>,
  override val symbolicIdIndex: SymbolicIdInternalIndex,
  override val externalMappings: Map<ExternalMappingKey<*>, ImmutableExternalEntityMappingImpl<*>>
) : AbstractStorageIndexes() {
  companion object {
    val EMPTY = ImmutableStorageIndexes(ImmutableMultimapStorageIndex(), VirtualFileIndex(), EntityStorageInternalIndex(false), SymbolicIdInternalIndex(),
                                        HashMap())
  }

  constructor(softLinks: ImmutableMultimapStorageIndex,
              virtualFileIndex: VirtualFileIndex,
              entitySourceIndex: EntityStorageInternalIndex<EntitySource>,
              symbolicIdIndex: SymbolicIdInternalIndex
  ) : this(softLinks, virtualFileIndex, entitySourceIndex, symbolicIdIndex, emptyMap())

  fun toMutable(): MutableStorageIndexes {
    val copiedSoftLinks = MutableMultimapStorageIndex.from(softLinks)
    val copiedVirtualFileIndex = VirtualFileIndex.MutableVirtualFileIndex.from(virtualFileIndex)
    val copiedEntitySourceIndex = EntityStorageInternalIndex.MutableEntityStorageInternalIndex.from(entitySourceIndex)
    val copiedSymbolicIdIndex = SymbolicIdInternalIndex.MutableSymbolicIdInternalIndex.from(symbolicIdIndex)
    val copiedExternalMappings = MutableExternalEntityMappingImpl.fromMap(externalMappings)
    return MutableStorageIndexes(copiedSoftLinks, copiedVirtualFileIndex, copiedEntitySourceIndex, copiedSymbolicIdIndex,
                                 copiedExternalMappings)
  }
}

internal class MutableStorageIndexes(
  override val softLinks: MutableMultimapStorageIndex,
  override val virtualFileIndex: VirtualFileIndex.MutableVirtualFileIndex,
  override val entitySourceIndex: EntityStorageInternalIndex.MutableEntityStorageInternalIndex<EntitySource>,
  override val symbolicIdIndex: SymbolicIdInternalIndex.MutableSymbolicIdInternalIndex,
  override val externalMappings: MutableMap<ExternalMappingKey<*>, MutableExternalEntityMappingImpl<*>>,
) : AbstractStorageIndexes() {

  fun <T : WorkspaceEntity> entityAdded(entityData: WorkspaceEntityData<T>, symbolicId: SymbolicEntityId<*>?) {
    val pid = entityData.createEntityId()

    // Update soft links index
    if (entityData is SoftLinkable) {
      entityData.index(softLinks)
    }

    val entitySource = entityData.entitySource
    entitySourceIndex.index(pid, entitySource)

    if (symbolicId != null) {
      symbolicIdIndex.index(pid, symbolicId)
    }

    entitySource.virtualFileUrl?.let { virtualFileIndex.index(pid, VIRTUAL_FILE_INDEX_ENTITY_SOURCE_PROPERTY, it) }
  }

  fun entityRemoved(entityId: EntityId) {
    entitySourceIndex.index(entityId)
    symbolicIdIndex.index(entityId)
    virtualFileIndex.removeRecordsByEntityId(entityId)
    externalMappings.values.forEach { it.remove(entityId) }
  }

  fun updateSoftLinksIndex(softLinkable: SoftLinkable) {
    softLinkable.index(softLinks)
  }

  fun removeFromSoftLinksIndex(softLinkable: SoftLinkable) {
    val pid = (softLinkable as WorkspaceEntityData<*>).createEntityId()

    for (link in softLinkable.getLinks()) {
      softLinks.remove(pid, link)
    }
  }

  fun updateIndices(oldEntityId: EntityId, newEntityData: WorkspaceEntityData<*>, builder: AbstractEntityStorage) {
    val newEntityId = newEntityData.createEntityId()
    virtualFileIndex.updateIndex(oldEntityId, newEntityId, builder.indexes.virtualFileIndex)
    entitySourceIndex.index(newEntityId, newEntityData.entitySource)
    builder.indexes.symbolicIdIndex.getEntryById(oldEntityId)?.also { symbolicIdIndex.index(newEntityId, it) }
  }

  private fun <T : WorkspaceEntity> simpleUpdateSoftReferences(copiedData: WorkspaceEntityData<T>, modifiableEntity: ModifiableWorkspaceEntityBase<*, *>?) {
    val pid = copiedData.createEntityId()
    if (copiedData is SoftLinkable) {
//      if (modifiableEntity is ModifiableModuleEntity && !modifiableEntity.dependencyChanged) return
//      if (modifiableEntity is ModifiableModuleEntity) return

      copiedData.updateLinksIndex(this.softLinks.getEntriesById(pid), this.softLinks)
    }
  }

  fun applyExternalMappingChanges(diff: MutableEntityStorageImpl,
                                  replaceMap: HashBiMap<NotThisEntityId, ThisEntityId>,
                                  target: MutableEntityStorageImpl) {
    diff.indexes.externalMappings.keys.asSequence().filterNot { it in externalMappings.keys }.forEach {
      externalMappings[it] = MutableExternalEntityMappingImpl<Any>()
    }

    diff.indexes.externalMappings.forEach { (identifier, index) ->
      val mapping = externalMappings[identifier]
      if (mapping != null) {
        mapping.applyChanges(index, replaceMap, target)
        if (mapping.index.isEmpty()) {
          externalMappings.remove(identifier)
        }
      }
    }
  }

  @Suppress("UNCHECKED_CAST")
  fun updateExternalMappingForEntityId(replaceWithEntityId: EntityId, targetEntityId: EntityId, replaceWithIndexes: AbstractStorageIndexes) {
    replaceWithIndexes.externalMappings.forEach { (mappingId, mapping) ->
      val data = mapping.index[replaceWithEntityId] ?: return@forEach
      val externalMapping = externalMappings[mappingId]
      if (externalMapping == null) {
        val newMapping = MutableExternalEntityMappingImpl<Any>()
        newMapping.add(targetEntityId, data)
        externalMappings[mappingId] = newMapping
      } else {
        externalMapping as MutableExternalEntityMappingImpl<Any>
        externalMapping.add(targetEntityId, data)
      }
    }
  }

  fun <T : WorkspaceEntity> updateSymbolicIdIndexes(builder: MutableEntityStorageImpl,
                                                    updatedEntity: WorkspaceEntity,
                                                    beforeSymbolicId: SymbolicEntityId<*>?,
                                                    copiedData: WorkspaceEntityData<T>,
                                                    modifiableEntity: ModifiableWorkspaceEntityBase<*, *>? = null) {
    val entityId = (updatedEntity as WorkspaceEntityBase).id
    if (updatedEntity is WorkspaceEntityWithSymbolicId) {
      val newSymbolicId = updatedEntity.symbolicId
      if (beforeSymbolicId != null && beforeSymbolicId != newSymbolicId) {
        symbolicIdIndex.index(entityId, newSymbolicId)
        updateComposedIds(builder, beforeSymbolicId, newSymbolicId)
      }
    }
    simpleUpdateSoftReferences(copiedData, modifiableEntity)
  }

  private fun updateComposedIds(builder: MutableEntityStorageImpl,
                                beforeSymbolicId: SymbolicEntityId<*>,
                                newSymbolicId: SymbolicEntityId<*>) {
    val idsWithSoftRef = HashSet(this.softLinks.getIdsByEntry(beforeSymbolicId))
    for (entityId in idsWithSoftRef) {
      val originalEntityData = builder.getOriginalEntityData(entityId) as WorkspaceEntityData<WorkspaceEntity>
      val entity = builder.entitiesByType.getEntityDataForModification(entityId) as WorkspaceEntityData<WorkspaceEntity>
      val editingBeforeSymbolicId = (entity.createEntity(builder) as? WorkspaceEntityWithSymbolicId)?.symbolicId
      (entity as SoftLinkable).updateLink(beforeSymbolicId, newSymbolicId)

      // Add an entry to changelog
      builder.changeLog.addReplaceDataEvent(entityId, entity, originalEntityData)
      // TODO :: Avoid updating of all soft links for the dependent entity
      builder.indexes.updateSymbolicIdIndexes(builder, entity.createEntity(builder), editingBeforeSymbolicId, entity)
    }
  }

  fun toImmutable(): ImmutableStorageIndexes {
    val copiedLinks = this.softLinks.toImmutable()
    val newVirtualFileIndex = virtualFileIndex.toImmutable()
    val newEntitySourceIndex = entitySourceIndex.toImmutable()
    val newSymbolicIdIndex = symbolicIdIndex.toImmutable()
    val newExternalMappings = MutableExternalEntityMappingImpl.toImmutable(externalMappings)
    return ImmutableStorageIndexes(copiedLinks, newVirtualFileIndex, newEntitySourceIndex, newSymbolicIdIndex, newExternalMappings)
  }
}
