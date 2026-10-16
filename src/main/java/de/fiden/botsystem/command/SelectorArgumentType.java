package de.fiden.botsystem.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;

public final class SelectorArgumentType implements CustomArgumentType<String, String> {
    private static final SimpleCommandExceptionType NEED_RADIUS = new SimpleCommandExceptionType(
        Component.literal("near-all braucht einen Radius, z.B. near-all 10")
    );

    public static SelectorArgumentType selector() {
        return new SelectorArgumentType();
    }

    @Override
    public ArgumentType<String> getNativeType() {
        return StringArgumentType.string();
    }

    public static String get(CommandContext<?> context, String name) {
        return context.getArgument(name, String.class);
    }

    public String parse(StringReader reader) throws CommandSyntaxException {
        String token = word(reader);
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.startsWith("@")) {
            lower = lower.substring(1);
        }

        if (!lower.equals("near-all") && !lower.equals("nearall") && !lower.equals("near")) {
            return token;
        } else {
            reader.skipWhitespace();
            String radius = word(reader);
            if (radius.isEmpty()) {
                throw NEED_RADIUS.createWithContext(reader);
            } else {
                return "@near:" + radius.replace(',', '.');
            }
        }
    }

    private static String word(StringReader reader) {
        int start = reader.getCursor();

        while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
            reader.skip();
        }

        return reader.getString().substring(start, reader.getCursor());
    }

    @Override
    public Collection<String> getExamples() {
        return List.of("@a", "@guards", "@team:Rot", "@near:10", "near-all 10", "Rotkaempfer");
    }
}
