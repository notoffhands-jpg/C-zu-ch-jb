"""Yarn -> Mojang source port for the BotSystem Fabric sources.

Reads the Fabric sources, renames Minecraft classes/methods/fields from Yarn names to Mojang names using the
tiny mapping files in the loom cache (official/intermediary/yarn + official/intermediary/mojang), and writes the
result into the Paper project. Ambiguous member names are resolved by an override table or left alone (javac
tells us afterwards). Fabric-specific code (mixins, client) is skipped; Fabric API calls are patched by hand.
"""
import os, re, sys, collections

FABRIC = r'C:/Users/notof/BotSystem-Fabric/src/main/java/de/fiden/botsystem'
PAPER = r'C:/Users/notof/BotSystem-Paper/src/main/java/de/fiden/botsystem'
YARN = r'C:/Users/notof/.gradle/caches/modules-2/files-2.1/net.fabricmc/yarn/1.21.11+build.6/623a9304295dc0128e0543b558bb46c01ac81959/yarn-1.21.11+build.6-v2.jar'
MOJ = r'C:/Users/notof/.gradle/caches/fabric-loom/1.21.11/loom.mappings.1_21_11.layered+hash.2198-v2/mappings.tiny'
SKIP_DIRS = {'mixin', 'client'}
SKIP_FILES = {'FakeConnections.java'}
BLACKLIST = {'isPresent','readDouble','removeIf','createDirectories','MIN_VALUE','currentTimeMillis','readInt','getCollectionCount','addAndGet','getCause','order','isNaN','readFloat','findFirst','ifPresent','floorMod','peek','readLong','requires','readBoolean','writeBoolean','readVarInt','writeVarInt','readString','writeInt','writeDouble','writeFloat','writeLong'}

def read_tiny(text):
    lines = text.splitlines()
    header = lines[0].split('	')  # tiny 2 0 <namespaces...>
    namespaces = header[3:]
    ii = namespaces.index('intermediary')
    ni = namespaces.index('named')
    classes = {}   # intermediary class -> named class
    members = []   # (kind, intermediary member, named member, intermediary class)
    cur = None
    for line in lines[1:]:
        if line.startswith('c	'):
            parts = line.split('	')[1:]
            cur = parts[ii]
            classes[cur] = parts[ni] if len(parts) > ni and parts[ni] else cur
        elif line.startswith('	m	') or line.startswith('	f	'):
            parts = line.split('	')
            kind = parts[1]
            names = parts[3:]  # desc at parts[2], then one name per namespace
            inter = names[ii] if len(names) > ii else None
            named = names[ni] if len(names) > ni and names[ni] else inter
            if inter:
                members.append((kind, inter, named, cur))
    return classes, members

import zipfile
yarn_text = zipfile.ZipFile(YARN).read('mappings/mappings.tiny').decode('utf-8')
moj_text = open(MOJ, encoding='utf-8').read()
yarn_classes, yarn_members = read_tiny(yarn_text)
moj_classes, moj_members = read_tiny(moj_text)

# class map: yarn FQCN -> mojang FQCN (only net/minecraft, skip inner-class-less duplicates)
class_map = {}
for inter, yarn in yarn_classes.items():
    moj = moj_classes.get(inter)
    if moj and yarn != moj:
        class_map[yarn.replace('/', '.').replace('$', '.')] = moj.replace('/', '.').replace('$', '.')

# member maps: yarn name -> Counter(mojang name)
moj_by_inter = {(k, i): n for k, i, n, c in moj_members}
method_map = collections.defaultdict(collections.Counter)
field_map = collections.defaultdict(collections.Counter)
for kind, inter, yarn, cls in yarn_members:
    moj = moj_by_inter.get((kind, inter))
    if not moj:
        continue
    yarn_simple = yarn.split('/')[-1]
    moj_simple = moj.split('/')[-1]
    (method_map if kind == 'm' else field_map)[yarn_simple][moj_simple] += 1

# Hand overrides for ambiguous / context-dependent names (yarn -> mojang) as used in this codebase.
OVERRIDES_M = {
    'getWorld': 'level', 'getEntityWorld': 'level', 'getEntityPos': 'position', 'getBlockPos': 'blockPosition',
    'getEntityName': 'getScoreboardName', 'getUuid': 'getUUID', 'getUuidAsString': 'getStringUUID',
    'squaredDistanceTo': 'distanceToSqr', 'distanceTo': 'distanceTo', 'getEyePos': 'getEyePosition',
    'getRotationVector': 'getLookAngle', 'getRotationClient': 'getRotationVector', 'getHeadYaw': 'getYHeadRot',
    'setHeadYaw': 'setYHeadRot', 'getYaw': 'getYRot', 'setYaw': 'setYRot', 'getPitch': 'getXRot', 'setPitch': 'setXRot',
    'getBodyYaw': 'getVisualRotationYInDegrees', 'setBodyYaw': 'setYBodyRot', 'isOnGround': 'onGround', 'setOnGround': 'setOnGround',
    'getVelocity': 'getDeltaMovement', 'setVelocity': 'setDeltaMovement', 'isSneaking': 'isShiftKeyDown', 'setSneaking': 'setShiftKeyDown',
    'isSneaky': 'isCrouching', 'isSprinting': 'isSprinting', 'setSprinting': 'setSprinting', 'isTouchingWater': 'isInWater',
    'isSubmergedInWater': 'isUnderWater', 'isInLava': 'isInLava', 'isAlive': 'isAlive', 'isRemoved': 'isRemoved', 'isDead': 'isDeadOrDying',
    'getHealth': 'getHealth', 'setHealth': 'setHealth', 'getMaxHealth': 'getMaxHealth', 'getAttacker': 'getLastHurtByMob',
    'getAttacking': 'getLastHurtMob', 'getMainHandStack': 'getMainHandItem', 'getOffHandStack': 'getOffhandItem',
    'getStackInHand': 'getItemInHand', 'setStackInHand': 'setItemInHand', 'getEquippedStack': 'getItemBySlot', 'equipStack': 'setItemSlot',
    'getInventory': 'getInventory', 'getSelectedSlot': 'getSelectedSlot', 'setSelectedSlot': 'setSelectedSlot',
    'getActiveItem': 'getUseItem', 'isUsingItem': 'isUsingItem', 'setCurrentHand': 'startUsingItem', 'clearActiveItem': 'stopUsingItem',
    'stopUsingItem': 'releaseUsingItem', 'getItemUseTime': 'getTicksUsingItem', 'isBlocking': 'isBlocking', 'swingHand': 'swing',
    'getHungerManager': 'getFoodData', 'getFoodLevel': 'getFoodLevel', 'setFoodLevel': 'setFoodLevel', 'getSaturationLevel': 'getSaturationLevel',
    'setSaturationLevel': 'setSaturation', 'getGameProfile': 'getGameProfile', 'getDisplayName': 'getDisplayName', 'getName': 'getName',
    'getCustomName': 'getCustomName', 'setCustomName': 'setCustomName', 'setCustomNameVisible': 'setCustomNameVisible',
    'sendMessage': 'sendSystemMessage', 'getServer': 'getServer', 'getPlayerManager': 'getPlayerList', 'getPlayerList': 'getPlayers',
    'getPlayer': 'getPlayer', 'getPlayers': 'players', 'getTicks': 'getTickCount', 'getOverworld': 'overworld', 'getWorlds': 'getAllLevels',
    'getRegistryKey': 'dimension', 'getRegistryManager': 'registryAccess', 'getBlockState': 'getBlockState', 'setBlockState': 'setBlock',
    'getFluidState': 'getFluidState', 'isAir': 'isAir', 'isOf': 'is', 'isIn': 'is', 'getBlock': 'getBlock', 'getCollisionShape': 'getCollisionShape',
    'isEmpty': 'isEmpty', 'isSolidBlock': 'isSolidRender', 'isFullCube': 'isCollisionShapeFullBlock', 'getEntity': 'getEntity',
    'getOtherEntities': 'getEntities', 'getEntitiesByClass': 'getEntitiesOfClass', 'iterateEntities': 'getAllEntities',
    'spawnEntity': 'addFreshEntity', 'getChunkManager': 'getChunkSource', 'isChunkLoaded': 'hasChunk', 'getWorldChunk': 'getChunk',
    'getTopY': 'getHeight', 'getBottomY': 'getMinY', 'getSeaLevel': 'getSeaLevel', 'getTime': 'getGameTime', 'getTimeOfDay': 'getDayTime',
    'getRandom': 'getRandom', 'nextInt': 'nextInt', 'nextFloat': 'nextFloat', 'nextDouble': 'nextDouble', 'nextBoolean': 'nextBoolean',
    'getGameRules': 'getGameRules', 'getScoreboard': 'getScoreboard', 'getScoreboardTeam': 'getTeam', 'isSpectator': 'isSpectator',
    'isCreative': 'isCreative', 'getAbilities': 'getAbilities', 'getPos': 'position', 'getPosition': 'getPosition', 'getRotation': 'getRotation',
    'getX': 'getX', 'getY': 'getY', 'getZ': 'getZ', 'getBlockX': 'getBlockX', 'getBlockY': 'getBlockY', 'getBlockZ': 'getBlockZ',
    'getBoundingBox': 'getBoundingBox', 'getWidth': 'getBbWidth', 'getHeight': 'getBbHeight', 'getStandingEyeHeight': 'getEyeHeight',
    'getEyeHeight': 'getEyeHeight', 'setPosition': 'setPos', 'setPos': 'setPosRaw', 'refreshPositionAndAngles': 'snapTo', 'refreshPositionAfterTeleport': 'teleportTo',
    'requestTeleport': 'teleportTo', 'teleport': 'teleportTo', 'lookAt': 'lookAt', 'getServerWorld': 'level', 'canSee': 'hasLineOfSight',
    'damage': 'hurt', 'kill': 'kill', 'discard': 'discard', 'remove': 'remove', 'attack': 'attack', 'tryAttack': 'doHurtTarget',
    'isInvulnerableTo': 'isInvulnerableTo', 'isInvulnerable': 'isInvulnerable', 'setInvulnerable': 'setInvulnerable', 'isInvisible': 'isInvisible',
    'getStatusEffect': 'getEffect', 'hasStatusEffect': 'hasEffect', 'addStatusEffect': 'addEffect', 'removeStatusEffect': 'removeEffect',
    'getAttributeInstance': 'getAttribute', 'getAttributeValue': 'getAttributeValue', 'getBaseValue': 'getBaseValue', 'setBaseValue': 'setBaseValue',
    'getTarget': 'getTarget', 'setTarget': 'setTarget', 'getNavigation': 'getNavigation', 'startMovingTo': 'moveTo', 'stop': 'stop',
    'isIdle': 'isDone', 'getBrain': 'getBrain', 'getLookControl': 'getLookControl', 'getMoveControl': 'getMoveControl', 'getJumpControl': 'getJumpControl',
    'setAiDisabled': 'setNoAi', 'isAiDisabled': 'isNoAi', 'setPersistent': 'setPersistenceRequired', 'isPersistent': 'isPersistenceRequired',
    'getType': 'getType', 'getId': 'getId', 'getEntityId': 'getId', 'getCommandSource': 'createCommandSourceStack', 'getCommandManager': 'getCommands',
    'getDispatcher': 'getDispatcher', 'parseAndExecute': 'performPrefixedCommand', 'getSource': 'getSource', 'sendFeedback': 'sendSuccess',
    'sendError': 'sendFailure', 'getPlayerOrThrow': 'getPlayerOrException', 'hasPermissionLevel': 'hasPermission', 'withPosition': 'withPosition',
    'withWorld': 'withLevel', 'withRotation': 'withRotation', 'withEntity': 'withEntity', 'withSilent': 'withSuppressedOutput', 'literal': 'literal',
    'formatted': 'withStyle', 'append': 'append', 'getString': 'getString', 'asOrderedText': 'getVisualOrderText', 'copy': 'copy',
    'getItem': 'getItem', 'getCount': 'getCount', 'setCount': 'setCount', 'decrement': 'shrink', 'increment': 'grow', 'copyWithCount': 'copyWithCount',
    'isDamageable': 'isDamageableItem', 'getDamage': 'getDamageValue', 'setDamage': 'setDamageValue', 'getMaxDamage': 'getMaxDamage',
    'isFood': 'isEdible', 'getComponents': 'getComponents', 'get': 'get', 'set': 'set', 'getDefaultStack': 'getDefaultInstance',
    'getStack': 'getItem', 'setStack': 'setItem', 'getMainHandStack': 'getMainHandItem',
    'markDirty': 'setChanged', 'getEmptySlot': 'getFreeSlot', 'getPickedUpItems': 'getPickedUpItems',
    'addExperience': 'giveExperiencePoints', 'getSyncedExperience': 'getExperienceLevel', 'setExperienceLevel': 'setExperienceLevels',
    'getWorldSpawnPos': 'getRespawnData', 'getSpawnPos': 'getSharedSpawnPos', 'getSpawnAngle': 'getSharedSpawnAngle',
    'getAverageTickTime': 'getAverageTickTimeNanos', 'getTickTime': 'getTickTime', 'getRunDirectory': 'getServerDirectory',
    'getSavePath': 'getWorldPath', 'getPath': 'getPath', 'isOnThread': 'isSameThread', 'execute': 'execute', 'getThread': 'getRunningThread',
    'getRegistry': 'lookupOrThrow', 'getOrThrow': 'getOrThrow', 'getEntry': 'get', 'getKey': 'getKey', 'value': 'value',
    'getPath': 'getPath', 'getNamespace': 'getNamespace', 'toString': 'toString', 'of': 'of', 'ofVanilla': 'withDefaultNamespace',
    'tryParse': 'tryParse', 'method_5477': 'getName', 'getDamageSources': 'damageSources', 'getRecentDamageSource': 'getLastDamageSource',
    'getAttackerFromSource': 'getEntity', 'getSourceEntity': 'getDirectEntity', 'getTypeRegistryEntry': 'typeHolder', 'isDirect': 'isDirect',
    'getInteractionManager': 'gameMode', 'changeGameMode': 'setGameMode', 'getGameMode': 'getGameModeForPlayer', 'tryBreakBlock': 'destroyBlock',
    'breakBlock': 'destroyBlock', 'openHandledScreen': 'openMenu', 'closeHandledScreen': 'closeContainer', 'sendContentUpdates': 'broadcastChanges',
    'getPlayerByUuid': 'getPlayer', 'isPlayer': 'isPlayer', 'getFreeSpaces': 'getFreeSlot', 'onPlayerConnect': 'placeNewPlayer',
    'getCurrentPlayerCount': 'getPlayerCount', 'getMaxPlayerCount': 'getMaxPlayers', 'broadcast': 'broadcastSystemMessage', 'sendToAll': 'broadcastAll',
    'sendToAround': 'broadcast', 'getPlayerManager': 'getPlayerList', 'getGameRules': 'getGameRules', 'getMainWorld': 'overworld',
    'getSpawnPoint': 'getRespawnData', 'getSkyAngle': 'getTimeOfDay', 'isNight': 'isNight', 'isDay': 'isBrightOutside', 'isRaining': 'isRaining',
    'getLightLevel': 'getBrightness', 'getTopPosition': 'getHeightmapPos', 'raycast': 'clip', 'getType': 'getType', 'getBlockPos': 'getBlockPos',
    'getSide': 'getDirection', 'getEntityCollisions': 'getEntityCollisions', 'isSpaceEmpty': 'noCollision', 'getStatusEffects': 'getActiveEffects',
    'getStepHeight': 'maxUpStep', 'isClimbing': 'onClimbable', 'isFallFlying': 'isFallFlying', 'isSwimming': 'isSwimming', 'setSwimming': 'setSwimming',
    'isInSwimmingPose': 'isVisuallySwimming', 'getPose': 'getPose', 'setPose': 'setPose', 'getFireTicks': 'getRemainingFireTicks', 'setFireTicks': 'setRemainingFireTicks',
    'isOnFire': 'isOnFire', 'extinguish': 'clearFire', 'getAir': 'getAirSupply', 'setAir': 'setAirSupply', 'getMaxAir': 'getMaxAirSupply',
    'getFallDistance': 'fallDistance', 'fallDistance': 'fallDistance', 'onLanding': 'resetFallDistance', 'getMovementSpeed': 'getSpeed', 'setMovementSpeed': 'setSpeed',
    'isTouchingWaterOrRain': 'isInWaterOrRain', 'shouldSwimInFluids': 'canSwimInFluidType', 'updateSwimming': 'updateSwimming', 'canWalkOnFluid': 'canStandOnFluid',
    'isInsideWall': 'isInWall', 'getHandItems': 'getHandSlots', 'getArmorItems': 'getArmorSlots', 'getOwner': 'getOwner', 'getUuidAsString': 'getStringUUID',
    'isTeammate': 'isAlliedTo', 'isTeamPlayer': 'isAlliedTo', 'getSteppingPos': 'getOnPos', 'getLandingPos': 'getOnPosLegacy', 'getBlockStateAtPos': 'getInBlockState',
    'tick': 'tick', 'baseTick': 'baseTick', 'tickMovement': 'aiStep', 'travel': 'travel', 'move': 'move', 'jump': 'jumpFromGround', 'getJumpVelocity': 'getJumpPower',
    'getSafeFallDistance': 'getMaxFallDistance', 'takeKnockback': 'knockback', 'onDeath': 'die', 'dropInventory': 'dropEquipment', 'getExperienceToDrop': 'getBaseExperienceReward',
    'shouldDropXp': 'shouldDropExperience', 'isInTeleportationState': 'isChangingDimension', 'isControlledByPlayer': 'isControlledByLocalInstance',
    'canReceiveWaypoints': 'isReceivingWaypoints', 'hasWaypoint': 'hasWaypoint', 'getWaypointConfig': 'waypointIcon', 'sendPickup': 'take',
    'getScoreboardTeam': 'getTeam', 'getDataTracker': 'getEntityData', 'getPlayerListName': 'getTabListDisplayName', 'sendAbilitiesUpdate': 'onUpdateAbilities',
    'setGameMode': 'setGameMode', 'getServerGameMode': 'getGameModeForPlayer', 'isOnGround': 'onGround', 'getMaxNetherPortalTime': 'getPortalWaitTime',
    'canTakeDamage': 'canBeSeenAsEnemy', 'canBeHitByProjectile': 'canBeHitByProjectile', 'collidesWith': 'canCollideWith', 'isCollidable': 'canBeCollidedWith',
    'isPushable': 'isPushable', 'pushAwayFrom': 'push', 'tickCramming': 'pushEntities', 'getMoveEffect': 'getMovementEmission', 'isLogicalSideForUpdatingMovement': 'isLocalInstanceAuthoritative',
    'getTeamColorValue': 'getTeamColor', 'getStatusEffectInstance': 'getEffect', 'getMovementSpeed': 'getSpeed', 'clearStatusEffects': 'removeAllEffects',
    'getHandSwingProgress': 'getAttackAnim', 'resetLastAttackedTicks': 'resetAttackStrengthTicker', 'getAttackCooldownProgress': 'getAttackStrengthScale',
    'getLastAttackedTime': 'getLastHurtMobTimestamp', 'getLastAttackTime': 'getLastHurtByMobTimestamp', 'setAttacker': 'setLastHurtByMob', 'setAttacking': 'setLastHurtMob',
    'getEntityType': 'getType', 'isLiving': 'isAlive', 'getEyeY': 'getEyeY', 'getLerpedPos': 'getPosition', 'getRotationVecClient': 'getViewVector',
    'getHorizontalFacing': 'getDirection', 'getMovementDirection': 'getMotionDirection', 'getFacing': 'getDirection', 'horizontalCollision': 'horizontalCollision',
    'verticalCollision': 'verticalCollision', 'noClip': 'noPhysics', 'isGlowing': 'isCurrentlyGlowing', 'setGlowing': 'setGlowingTag', 'setSilent': 'setSilent',
    'setNoGravity': 'setNoGravity', 'hasNoGravity': 'isNoGravity', 'getScoreboardTags': 'getTags', 'addCommandTag': 'addTag', 'removeCommandTag': 'removeTag',
    'getPassengerList': 'getPassengers', 'hasPassengers': 'isVehicle', 'hasVehicle': 'isPassenger', 'getVehicle': 'getVehicle', 'startRiding': 'startRiding',
    'stopRiding': 'stopRiding', 'dismountVehicle': 'removeVehicle', 'getControllingPassenger': 'getControllingPassenger', 'getFirstPassenger': 'getFirstPassenger',
    'shouldRenderName': 'shouldShowName', 'isCustomNameVisible': 'isCustomNameVisible', 'hasCustomName': 'hasCustomName', 'getSavedEntityId': 'getEncodeId',
    'writeNbt': 'saveWithoutId', 'readNbt': 'load', 'writeCustomDataToNbt': 'addAdditionalSaveData', 'readCustomDataFromNbt': 'readAdditionalSaveData',
    'toNbt': 'save', 'fromNbt': 'load', 'getNbt': 'getTag', 'getCompound': 'getCompound', 'getList': 'getList', 'putString': 'putString', 'getString': 'getString',
    'putInt': 'putInt', 'getInt': 'getInt', 'putDouble': 'putDouble', 'getDouble': 'getDouble', 'putBoolean': 'putBoolean', 'getBoolean': 'getBoolean', 'put': 'put',
    'getVec3d': 'getVec3', 'getDefaultAttributes': 'getDefaultAttributes', 'createLivingAttributes': 'createLivingAttributes',
    'getRangedAttackTarget': 'getTarget', 'canTarget': 'canAttack', 'getVisibilityCache': 'getSensing', 'getGoalSelector': 'goalSelector',
    'getTargetSelector': 'targetSelector', 'getGoals': 'getAvailableGoals', 'getGoal': 'getGoal', 'getPriority': 'getPriority', 'disableControl': 'disableControlFlag',
    'shouldContinue': 'canContinueToUse', 'canStart': 'canUse', 'getControls': 'getFlags', 'findPathTo': 'createPath', 'findPathToAny': 'createPath', 'getCurrentPath': 'getPath',
    'isFollowingPath': 'isInProgress', 'recalculatePath': 'recomputePath', 'setSpeed': 'setSpeedModifier', 'getNodeMaker': 'getNodeEvaluator', 'setCanSwim': 'setCanFloat',
    'setCanOpenDoors': 'setCanOpenDoors', 'setCanEnterOpenDoors': 'setCanPassDoors', 'getLength': 'getNodeCount', 'getCurrentNodeIndex': 'getNextNodeIndex',
    'setCurrentNodeIndex': 'setNextNodeIndex', 'getCurrentNode': 'getNextNode', 'getNode': 'getNode', 'getEnd': 'getEndNode', 'getTarget': 'getTarget', 'reachesTarget': 'canReach',
    'isFinished': 'isDone', 'getNodePosition': 'getEntityPosAtNode', 'getNodePos': 'getNodePos', 'getManhattanDistance': 'distanceManhattan',
    'setPenalty': 'costMalus', 'getDefaultPenalty': 'getDefaultNodeType', 'getNodeType': 'getPathType', 'getSuccessors': 'getNeighbors', 'getStart': 'getStart',
    'getPathfindingPenalty': 'getPathfindingMalus', 'setPathfindingPenalty': 'setPathfindingMalus', 'canPathfindThrough': 'getPathType', 
    'pathfind': 'findPath', 'getBlockView': 'getLevel', 'getChunk': 'getChunk', 'getDimension': 'dimensionType', 'isClient': 'isClientSide', 'getProfiler': 'getProfiler',
    'getRegistryEntry': 'getHolder', 'getIdAsString': 'getSerializedName', 'asString': 'getSerializedName', 'getEntityKey': 'builtInRegistryHolder',
    'ofBlock': 'byBlock', 'getRawId': 'getId', 'getIds': 'keySet', 'getEntrySet': 'entrySet', 'containsId': 'containsKey', 'getOrEmpty': 'getOptional', 'streamEntries': 'listElements',
    'getRandomEntry': 'getRandom', 'indexedEntries': 'holders', 'getIndexedEntries': 'holders', 'getTagOrThrow': 'getOrThrow', 'iterateEntries': 'getTagOrEmpty',
    'getVec3d': 'getVec3', 'getAngle': 'getRotation', 'getBlockLightLevel': 'getBrightness', 'method_37908': 'level', 'getPlayerEntity': 'getPlayer',
    'createSourceStack': 'createCommandSourceStack', 'getRotationVector': 'getLookAngle', 'fromPitchYaw': 'directionFromRotation', 'rotationOf': 'directionFromRotation',
    'isInRange': 'closerThan', 'getHorizontalLength': 'horizontalDistance', 'horizontalLengthSquared': 'horizontalDistanceSqr', 'lengthSquared': 'lengthSqr', 'length': 'length',
    'normalize': 'normalize', 'multiply': 'scale', 'subtract': 'subtract', 'dotProduct': 'dot', 'crossProduct': 'cross', 'lerp': 'lerp', 'withAxis': 'with',
    'getHorizontal': 'horizontal', 'rotateY': 'yRot', 'rotateX': 'xRot', 'relativize': 'vectorTo', 'ofCenter': 'atCenterOf', 'ofBottomCenter': 'atBottomCenterOf', 'toCenterPos': 'getCenter',
    'toBottomCenterPos': 'getBottomCenter', 'ofFloored': 'containing', 'down': 'below', 'up': 'above', 'getSquaredDistance': 'distSqr', 'getManhattanDistance': 'distManhattan',
    'isWithinDistance': 'closerThan', 'asLong': 'asLong', 'fromLong': 'of', 'toImmutable': 'immutable', 'toShortString': 'toShortString', 'iterate': 'betweenClosed', 'iterateOutwards': 'withinManhattan',
    'expand': 'inflate', 'contract': 'deflate', 'intersects': 'intersects', 'getCenter': 'getCenter', 'getLengthX': 'getXsize',
    'getLengthY': 'getYsize', 'getLengthZ': 'getZsize', 'stretch': 'expandTowards', 'raycast': 'clip', 'getMissed': 'miss', 'getBlockHitResult': 'getBlockHitResult',
    'getEntityHitResult': 'getEntityHitResult', 'floor': 'floor', 'ceil': 'ceil', 'clamp': 'clamp', 'sqrt': 'sqrt', 'wrapDegrees': 'wrapDegrees', 'subtractAngles': 'degreesDifference',
    'angleBetween': 'degreesDifferenceAbs', 'lerpAngleDegrees': 'rotLerp', 'nextBetween': 'nextInt', 'square': 'square', 'hypot': 'length', 'atan2': 'atan2', 'sin': 'sin', 'cos': 'cos',
    'fractionalPart': 'frac', 'approximatelyEquals': 'equal', 'getLerpProgress': 'inverseLerp', 'stepTowards': 'approach', 'stepUnwrappedTowards': 'approachDegrees',
}
OVERRIDES_F = {
    'forwardSpeed': 'zza', 'sidewaysSpeed': 'xxa', 'upwardSpeed': 'yya', 'jumping': 'jumping', 'age': 'tickCount', 'headYaw': 'yHeadRot', 'bodyYaw': 'yBodyRot',
    'prevYaw': 'yRotO', 'prevPitch': 'xRotO', 'prevX': 'xo', 'prevY': 'yo', 'prevZ': 'zo', 'horizontalCollision': 'horizontalCollision', 'verticalCollision': 'verticalCollision',
    'noClip': 'noPhysics', 'fallDistance': 'fallDistance', 'hurtTime': 'hurtTime', 'deathTime': 'deathTime', 'handSwinging': 'swinging', 'handSwingTicks': 'swingTime',
    'preferredHand': 'swingingArm', 'experienceLevel': 'experienceLevel', 'totalExperience': 'totalExperience', 'experienceProgress': 'experienceProgress',
    'networkHandler': 'connection', 'interactionManager': 'gameMode', 'currentScreenHandler': 'containerMenu', 'playerScreenHandler': 'inventoryMenu', 'server': 'server',
    'velocityModified': 'hurtMarked', 'velocityDirty': 'hasImpulse', 'knockedBack': 'hurtMarked', 'lastAttackedTicks': 'attackStrengthTicker', 'inputSpeed': 'speed',
    'random': 'random', 'x': 'x', 'y': 'y', 'z': 'z', 'goalSelector': 'goalSelector', 'targetSelector': 'targetSelector', 'navigation': 'navigation',
    'ARMOR': 'ARMOR', 'MAX_HEALTH': 'MAX_HEALTH', 'ATTACK_DAMAGE': 'ATTACK_DAMAGE', 'MOVEMENT_SPEED': 'MOVEMENT_SPEED', 'FOLLOW_RANGE': 'FOLLOW_RANGE', 'SAFE_FALL_DISTANCE': 'SAFE_FALL_DISTANCE',
    'GENERIC_FOLLOW_RANGE': 'FOLLOW_RANGE', 'STEP_HEIGHT': 'STEP_HEIGHT', 'KNOCKBACK_RESISTANCE': 'KNOCKBACK_RESISTANCE', 'ATTACK_SPEED': 'ATTACK_SPEED', 'ATTACK_KNOCKBACK': 'ATTACK_KNOCKBACK',
    'MAINHAND': 'MAINHAND', 'OFFHAND': 'OFFHAND', 'HEAD': 'HEAD', 'CHEST': 'CHEST', 'LEGS': 'LEGS', 'FEET': 'FEET', 'MAIN_HAND': 'MAIN_HAND', 'OFF_HAND': 'OFF_HAND',
    'SURVIVAL': 'SURVIVAL', 'CREATIVE': 'CREATIVE', 'SPECTATOR': 'SPECTATOR', 'ADVENTURE': 'ADVENTURE', 'OVERWORLD': 'OVERWORLD', 'NETHER': 'NETHER', 'END': 'END',
    'ZERO': 'ZERO', 'EMPTY': 'EMPTY', 'ORIGIN': 'ZERO', 'AIR': 'AIR', 'WATER': 'WATER', 'LAVA': 'LAVA', 'STONE': 'STONE', 'DIRT': 'DIRT', 'GRASS_BLOCK': 'GRASS_BLOCK',
    'SAND': 'SAND', 'GRAVEL': 'GRAVEL', 'COBBLESTONE': 'COBBLESTONE', 'OAK_PLANKS': 'OAK_PLANKS', 'BEDROCK': 'BEDROCK', 'NORTH': 'NORTH', 'SOUTH': 'SOUTH', 'EAST': 'EAST', 'WEST': 'WEST',
    'UP': 'UP', 'DOWN': 'DOWN', 'EXPLOSION': 'EXPLOSION', 'WORLD_SURFACE': 'WORLD_SURFACE', 'MOTION_BLOCKING': 'MOTION_BLOCKING', 'MOTION_BLOCKING_NO_LEAVES': 'MOTION_BLOCKING_NO_LEAVES',
    'OCEAN_FLOOR': 'OCEAN_FLOOR', 'PLAYER': 'PLAYER', 'ZOMBIE': 'ZOMBIE', 'WOLF': 'WOLF', 'RED': 'RED', 'GREEN': 'GREEN', 'BLUE': 'BLUE', 'YELLOW': 'YELLOW', 'GOLD': 'GOLD', 'GRAY': 'GRAY',
    'DARK_GRAY': 'DARK_GRAY', 'WHITE': 'WHITE', 'AQUA': 'AQUA', 'LIGHT_PURPLE': 'LIGHT_PURPLE', 'DARK_RED': 'DARK_RED', 'DARK_GREEN': 'DARK_GREEN', 'DARK_AQUA': 'DARK_AQUA',
    'DARK_PURPLE': 'DARK_PURPLE', 'BLACK': 'BLACK', 'BOLD': 'BOLD', 'ITALIC': 'ITALIC', 'UNDERLINE': 'UNDERLINE', 'STRIKETHROUGH': 'STRIKETHROUGH', 'RESET': 'RESET',
    'hasCustomName': 'hasCustomName', 'level': 'level', 'lastDamageSource': 'lastDamageSource', 'lastDamageTime': 'lastDamageStamp', 'lastAttackedTime': 'lastHurtByMobTimestamp',
    'maxHurtTime': 'hurtDuration', 'timeUntilRegen': 'invulnerableTime', 'stuckArrowTimer': 'removeArrowTime', 'defaultMaxHealth': 'DEFAULT_BASE_MAX_HEALTH',
    'pos': 'position', 'yaw': 'yRot', 'pitch': 'xRot', 'blockPos': 'blockPosition', 'lastRenderX': 'xOld', 'lastRenderY': 'yOld', 'lastRenderZ': 'zOld',
    'firstUpdate': 'firstTick', 'timeUntilRegen': 'invulnerableTime', 'onGround': 'onGround', 'inPowderSnow': 'isInPowderSnow', 'submergedInWater': 'wasUnderwater', 'touchingWater': 'wasTouchingWater',
    'speed': 'speed', 'sideways': 'xxa', 'forward': 'zza', 'movementSpeed': 'speed', 'stepHeight': 'maxUpStep', 'lastAttacker': 'lastHurtByPlayer', 'attacker': 'lastHurtByMob',
    'attacking': 'lastHurtMob', 'lastAttackTime': 'lastHurtByMobTimestamp', 'lastAttackedTicks': 'attackStrengthTicker', 'activeItemStack': 'useItem', 'itemUseTimeLeft': 'useItemRemaining',
    'dead': 'dead', 'despawnCounter': 'noActionTime', 'hungerManager': 'foodData', 'abilities': 'abilities', 'sleepTimer': 'sleepCounter', 'strikes': 'strikes', 'isSwimming': 'swimming',
    'field_6213': 'deathTime', 'CustomName': 'CustomName', 'displayName': 'displayName', 'nameLabelPos': 'nameTagAttachment',
}

# Namen, die unser eigener Code deklariert (Felder/Methoden): nie ueber die "eindeutige" Mapping-Schiene umbenennen.
OWN = set()
for root_, dirs_, files_ in os.walk(FABRIC):
    for f_ in files_:
        if f_.endswith('.java'):
            t_ = open(os.path.join(root_, f_), encoding='utf-8').read()
            OWN.update(re.findall(r'^\s*(?:public|private|protected|static|final|\s)+[A-Za-z0-9_<>\[\],.? ]+\s+([a-z][A-Za-z0-9_]*)\s*(?:=|;)', t_, re.M))
            OWN.update(re.findall(r'^\s*(?:public|private|protected|static|final|synchronized|\s)+[A-Za-z0-9_<>\[\],.? ]+\s+([a-z][A-Za-z0-9_]*)\s*\([^)]*\)\s*(?:throws [A-Za-z0-9_., ]+)?\s*\{', t_, re.M))
print('own names:', len(OWN))

fq_pattern = re.compile(r'\bnet\.minecraft(?:\.[A-Za-z_][A-Za-z0-9_]*)+')
report = collections.Counter()
unique_used = collections.Counter()
ambiguous = collections.Counter()

def map_fq(name):
    # longest prefix match on class names (handles nested classes via dotted names)
    if name in class_map:
        return class_map[name]
    parts = name.split('.')
    for cut in range(len(parts) - 1, 2, -1):
        head = '.'.join(parts[:cut])
        if head in class_map:
            return class_map[head] + '.' + '.'.join(parts[cut:])
    return name

def port_file(src, dst):
    text = open(src, encoding='utf-8').read()
    simple_renames = {}
    def fq_repl(m):
        old = m.group(0)
        new = map_fq(old)
        if new != old:
            os_, ns_ = old.split('.')[-1], new.split('.')[-1]
            if os_ != ns_:
                simple_renames[os_] = ns_
        return new
    text = fq_pattern.sub(fq_repl, text)
    # simple names imported/referenced -> rename word occurrences
    for old, new in simple_renames.items():
        text = re.sub(r'\b' + re.escape(old) + r'\b', new, text)
    # Paketpfade (net.minecraft..., java.util...) vor der Member-Umbenennung maskieren - sonst wird ".network" zu ".user".
    masks = []
    def mask(m):
        masks.append(m.group(0))
        return '@@MASK%d@@' % (len(masks) - 1)
    text = re.sub(r'\b(?:net|com|java|javax|de|io|org|it)\.[a-z][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+', mask, text)
    # member renames: identifiers preceded by '.' (and '::' for method refs)
    def member_repl(m):
        prefix, name, call = m.group(1), m.group(2), m.group(3)
        is_method = call == '('
        table = OVERRIDES_M if is_method else OVERRIDES_F
        if name in table:
            new = table[name]
        else:
            cands = (method_map if is_method else field_map).get(name)
            if not cands or name in OWN or name in BLACKLIST:
                return m.group(0)
            if len(cands) == 1:
                new = next(iter(cands))
                if new != name:
                    unique_used[(('m' if is_method else 'f'), name, new)] += 1
            else:
                ambiguous[(('m' if is_method else 'f'), name)] += 1
                return m.group(0)
        if new != name:
            report[(name, new)] += 1
        return prefix + new + call
    text = re.sub(r'(\.|::)([A-Za-z_][A-Za-z0-9_]*)(\s*\()?', lambda m: member_repl(m) if m.group(3) else member_repl_field(m), text)
    text = re.sub(r'@@MASK(\d+)@@', lambda m: masks[int(m.group(1))], text)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    open(dst, 'w', encoding='utf-8').write(text)

def member_repl_field(m):
    prefix, name = m.group(1), m.group(2)
    if name in OVERRIDES_F:
        new = OVERRIDES_F[name]
    else:
        cands = field_map.get(name)
        if not cands or name in OWN or name in BLACKLIST:
            return m.group(0)
        if len(cands) == 1:
            new = next(iter(cands))
            if new != name:
                unique_used[('f', name, new)] += 1
        else:
            ambiguous[('f', name)] += 1
            return m.group(0)
    if new != name:
        report[(name, new)] += 1
    return prefix + new

count = 0
for root, dirs, files in os.walk(FABRIC):
    rel = os.path.relpath(root, FABRIC).replace('\\', '/')
    if rel.split('/')[0] in SKIP_DIRS:
        continue
    for f in files:
        if not f.endswith('.java') or f in SKIP_FILES:
            continue
        port_file(os.path.join(root, f), os.path.join(PAPER, rel if rel != '.' else '', f))
        count += 1
print('ported files:', count)
print('top renames:', report.most_common(25))
print('ambiguous (left alone):', ambiguous.most_common(60))
print('UNIQUE-PATH RENAMES:')
for (k, a, b), n in sorted(unique_used.items(), key=lambda x: -x[1]):
    print('  %s %s -> %s (%d)' % (k, a, b, n))
