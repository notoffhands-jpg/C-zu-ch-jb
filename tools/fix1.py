import re, os, glob
base = 'C:/Users/notof/BotSystem-Paper/src/main/java/de/fiden/botsystem/'
files = glob.glob(base + '**/*.java', recursive=True)

GLOBAL = [
    (r'(\bsource|getSource\(\))\.level\(\)', r'\1.getLevel()'),
    (r'\.getBlockPos\(\)', '.blockPosition()'),
    (r'getNode\(([^()]*(?:\([^()]*\))?[^()]*)\)\.blockPosition\(\)', r'getNode(\1).asBlockPos()'),
    (r'BlockPos\.Mutable\b', 'BlockPos.MutableBlockPos'),
    (r'ChunkPos\.toLong\(', 'ChunkPos.asLong('),
    (r'ChunkPos\.getPackedX\(', 'ChunkPos.getX('),
    (r'ChunkPos\.getPackedZ\(', 'ChunkPos.getZ('),
    (r'\.getEntityPosAtNode\((\w+)\)', r'.getNextEntityPos(\1)'),
    (r'\.path\.next\(\)', '.path.advance()'),
    (r'Block\.NOTIFY_ALL\b', 'Block.UPDATE_ALL'),
    (r'\.getHardness\(', '.getDestroySpeed('),
    (r'shape\.getMax\(', 'shape.max('),
    (r'Files\.writeUtf\(', 'Files.writeString('),
    (r'\.getPlayerNames\(\)', '.getPlayerNamesArray()'),
    (r'BlockPos\.ofFloored\(', 'BlockPos.containing('),
    (r'\.getOptionalValue\(', '.getOptional('),
    (r'\.getDefaultState\(\)', '.defaultBlockState()'),
    (r'\.isReplaceable\(\)', '.canBeReplaced()'),
    (r'EntityType\.get\(', 'EntityType.byString('),
    (r'getMoveControl\(\)\.getSpeed\(\)', 'getMoveControl().getSpeedModifier()'),
    (r'getChunkSource\(\)\.getChunk\((\w+), (\w+)\)', r'getChunkSource().getChunkNow(\1, \2)'),
    (r'\.dimension\(\)\.getValue\(\)', '.dimension().identifier()'),
    (r'getInventory\(\)\.size\(\)', 'getInventory().getContainerSize()'),
    (r'inventory\.size\(\)', 'inventory.getContainerSize()'),
    (r'RegistryOps\.of\(', 'RegistryOps.create('),
    (r'BuiltInRegistries\.WORLD\b', 'net.minecraft.core.registries.Registries.DIMENSION'),
    (r'Identifier\.of\("([a-z_]+)", ', r'Identifier.fromNamespaceAndPath("\1", '),
    (r'source\.getLastHurtByMob\(\)', 'source.getEntity()'),
    (r'source\.getName\(\)', 'source.getMsgId()'),
    (r'\.connection\.sendPacket\(', '.connection.send('),
    (r'Holder\.of\(', 'Holder.direct('),
    (r'\.connection\.onDisconnected\(', '.connection.onDisconnect('),
    (r'CommonListenerCookie\.createDefault\(', 'CommonListenerCookie.createInitial('),
    (r'\.gameMode\.setGameMode\(', '.gameMode.changeGameModeForPlayer('),
    (r'\.createPath\(cache, dummy,', '.findPath(cache, dummy,'),
    (r'\bstart\.add\((-?\w+), (-?\w+), (-?\w+)\)', r'start.offset(\1, \2, \3)'),
    (r'\bfrom\.add\((-?\w+), (-?\w+), (-?\w+)\)', r'from.offset(\1, \2, \3)'),
    (r'\.getMainHandItem\(\)\.getName\(\)', '.getMainHandItem().getHoverName()'),
    (r'\.getOffhandItem\(\)\.getName\(\)', '.getOffhandItem().getHoverName()'),
    (r'getProjectile\(bot\.getMainHandItem\(\)\)\.getName\(\)', 'getProjectile(bot.getMainHandItem()).getHoverName()'),
    (r'\.clear\(goal ->', '.removeAllGoals(goal ->'),
    (r'\.get\((net\.minecraft\.world\.level\.block\.\w+\.OPEN)\)', r'.getValue(\1)'),
    (r'state\.with\((net\.minecraft\.world\.level\.block\.\w+\.OPEN), true\)', r'state.setValue(\1, true)'),
    (r'feet\.offset\(side, distance\)', 'feet.relative(side, distance)'),
    (r'ENTITY_TYPE\.getId\(', 'ENTITY_TYPE.getKey('),
]

for f in files:
    s = open(f, encoding='utf-8').read()
    o = s
    for pat, rep in GLOBAL:
        s = re.sub(pat, rep, s)
    if s != o:
        open(f, 'w', encoding='utf-8').write(s)
        print('patched', os.path.basename(f))

# Bots: anchors via Paper plugin tickets
p = base + 'bot/Bots.java'
s = open(p, encoding='utf-8').read()
start = s.index('   private static final net.minecraft.server.level.TicketType ANCHOR = net.minecraft.core.Registry.register(')
end = s.index('   /** Stufe 33 - 2 = 31')
s = s[:start] + '''   private static final net.minecraft.server.level.TicketType ANCHOR = net.minecraft.server.level.TicketType.PLUGIN_TICKET;
''' + s[end:]
s = s.replace('.getChunkSource().addTicket(ANCHOR, ', '.getChunkSource().addTicketWithRadius(ANCHOR, ')
s = s.replace('.getChunkSource().removeTicket(ANCHOR, ', '.getChunkSource().removeTicketWithRadius(ANCHOR, ')
open(p, 'w', encoding='utf-8').write(s)

# BotPlayer spawn: fake connection + isRealPlayer
p = base + 'bot/BotPlayer.java'
s = open(p, encoding='utf-8').read()
s = s.replace('''      Connection connection = new Connection(PacketFlow.SERVERBOUND);
      FakeConnections.mark(connection);
      server.getPlayerList().placeNewPlayer(connection, bot, CommonListenerCookie.createInitial(profile, false));''',
'''      Connection connection = FakeConnections.create();
      server.getPlayerList().placeNewPlayer(connection, bot, CommonListenerCookie.createInitial(profile, false));
      // Paper: kein echter Spieler -> kein Chunk-Laden/Senden fuer diesen "Client" (Moonrise fragt isRealPlayer).
      bot.isRealPlayer = false;''')
open(p, 'w', encoding='utf-8').write(s)
print('ok')
