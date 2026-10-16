import re, glob, os
base = 'C:/Users/notof/BotSystem-Paper/src/main/java/de/fiden/botsystem/'
def sub(path, pairs, regex=False):
    p = base + path; s = open(p, encoding='utf-8').read()
    for a, b in pairs:
        if regex:
            s2 = re.sub(a, b, s)
        else:
            assert a in s, (path, a[:60]); s2 = s.replace(a, b)
        s = s2
    open(p, 'w', encoding='utf-8').write(s)

sub('bot/BotPlayer.java', [
    ('   public void takeKnockback(double strength, double x, double z) {', '   public void knockback(double strength, double x, double z, net.minecraft.world.entity.Entity attacker, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause cause) {'),
    ('   public void setVelocity(Vec3 velocity) {', '   public void setDeltaMovement(Vec3 velocity) {'),
    ('   public boolean hasWaypoint() {\n      return false;\n   }', '   public boolean isTransmittingWaypoint() {\n      return false;\n   }'),
    ('   public boolean canReceiveWaypoints() {', '   public boolean isReceivingWaypoints() {'),
    ('   protected void tickCramming() {', '   protected void pushEntities() {'),
    ('   public boolean isInTeleportationState() {', '   public boolean isChangingDimension() {'),
    ('   @Override\n   public boolean isControlledByPlayer() {\n      return false;\n   }', '   public boolean isControlledByPlayer() {\n      return false;\n   }'),
    ('   protected void dropInventory(ServerLevel world) {', '   protected void dropEquipment(ServerLevel world) {'),
    ('   protected int getExperienceToDrop(ServerLevel world) {', '   protected int getBaseExperienceReward(ServerLevel world) {'),
    ('   public void onDeath(DamageSource source) {', '   public void die(DamageSource source) {'),
    ('      Connection connection = FakeConnections.create();', '      FakeConnections.FakeConnection connection = FakeConnections.create();'),
])
# super calls inside those overrides
s = open(base + 'bot/BotPlayer.java', encoding='utf-8').read()
s = s.replace('super.takeKnockback(', 'super.knockback(').replace('super.setVelocity(', 'super.setDeltaMovement(').replace('super.dropInventory(', 'super.dropEquipment(').replace('super.getExperienceToDrop(', 'super.getBaseExperienceReward(').replace('super.onDeath(', 'super.die(').replace('super.tickCramming(', 'super.pushEntities(')
s = s.replace('super.knockback(strength, x, z);', 'super.knockback(strength, x, z, attacker, cause);')
open(base + 'bot/BotPlayer.java', 'w', encoding='utf-8').write(s)

sub('ai/BotBrain.java', [('   private boolean breakBlock(BotPlayer bot, BlockPos target) {', '   private boolean destroyBlock(BotPlayer bot, BlockPos target) {')])
sub('bot/MobUnits.java', [
    ('   private void stepTowards(Unit unit, Vec3 goal) {', '   private void approach(Unit unit, Vec3 goal) {'),
    ('   private void stepTowards(Unit unit, Vec3 goal, double speed) {', '   private void approach(Unit unit, Vec3 goal, double speed) {'),
    ('mob.getJumpControl().setActive();', 'mob.getJumpControl().jump();'),
    ('mob.getLookControl().lookAt(watched, 30.0F, 30.0F);', 'mob.getLookControl().setLookAt(watched, 30.0F, 30.0F);'),
    ('mob.getLookControl().lookAt(prey, 30.0F, 30.0F);', 'mob.getLookControl().setLookAt(prey, 30.0F, 30.0F);'),
    ('mob.getMoveControl().moveTo(preyPos.x, preyPos.y, preyPos.z, 1.3);', 'mob.getMoveControl().setWantedPosition(preyPos.x, preyPos.y, preyPos.z, 1.3);'),
    ('mob.getMoveControl().moveTo(ground.x, ground.y, ground.z, speed);', 'mob.getMoveControl().setWantedPosition(ground.x, ground.y, ground.z, speed);'),
    ('world.getLocalDifficulty(mob.blockPosition())', 'world.getCurrentDifficultyAt(mob.blockPosition())'),
    ('prey.hurt(world, world.damageSources().mobAttack(mob), hit)', 'prey.hurtServer(world, world.damageSources().mobAttack(mob), hit)'),
])
sub('command/BotCommands.java', [
    ('hit.blockPosition()', 'hit.getBlockPos()'),
    ('unit.mob.getMoveControl().isMoving()', 'unit.mob.getMoveControl().hasWanted()'),
])
sub('ai/BotNavigator.java', [('               public int getSafeFallDistance() {', '               public int getMaxFallDistance() {')])
sub('bot/BotSaves.java', [
    ('Math.min(inventory.getContainerSize(), bot.getInventory().getContainerSize())', 'Math.min(inventory.size(), bot.getInventory().getContainerSize())'),
    ('net.minecraft.core.registries.net.minecraft.core.registries.Registries.DIMENSION', 'net.minecraft.core.registries.Registries.DIMENSION'),
])
sub('bot/Bots.java', [
    ('bot.getChunkPos().x, bot.getChunkPos().z', 'bot.chunkPosition().x, bot.chunkPosition().z'),
    ('unit.mob.getChunkPos().x, unit.mob.getChunkPos().z', 'unit.mob.chunkPosition().x, unit.mob.chunkPosition().z'),
    ('world.getRespawnData().position()', 'world.getRespawnData().pos()'),
])
sub('bot/FarmTarget.java', [('BuiltInRegistries.BLOCK.getId(this.block).getPath()', 'BuiltInRegistries.BLOCK.getKey(this.block).getPath()')])
sub('bot/FilmScene.java', [('this.position', 'this.pos'), ('o.position', 'o.pos')])
sub('bot/Ground.java', [
    ('world.getChunkSource().getChunk((int)Math.floor(x) >> 4, (int)Math.floor(z) >> 4) != null', 'world.getChunkSource().getChunkNow((int)Math.floor(x) >> 4, (int)Math.floor(z) >> 4) != null'),
    ('Heightmap.Type.', 'Heightmap.Types.'),
])
sub('bot/Kits.java', [('stack.getName()', 'stack.getHoverName()')])
sub('net/MapServer.java', [
    ('chunk.sampleHeightmap(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, lx, lz)', 'chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, lx, lz)'),
    ('above.sampleHeightmap(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_', 'above.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_'),
    ('.getMapColor(world, pos).color', '.getMapColor(world, pos).col'),
])
sub('gui/SettingsMenu.java', [
    ('this.getInventory().setItem(', 'this.getContainer().setItem('),
    ('.styled(style -> style.withItalic(false))', '.withStyle(style -> style.withItalic(false))'),
    ('this.viewer::closeHandledScreen', 'this.viewer::closeContainer'),
    ('   public void onSlotClick(int slot, int button, ClickType actionType, Player player) {', '   public void clicked(int slot, int button, ClickType actionType, Player player) {'),
    ('   public ItemStack quickMove(Player player, int slot) {', '   public ItemStack quickMoveStack(Player player, int slot) {'),
    ('   public boolean canUse(Player player) {', '   public boolean stillValid(Player player) {'),
])
print('ok')
