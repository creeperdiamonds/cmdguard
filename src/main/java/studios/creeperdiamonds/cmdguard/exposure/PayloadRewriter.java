package studios.creeperdiamonds.cmdguard.exposure;

import net.fabricmc.fabric.impl.attachment.sync.c2s.AcceptedAttachmentsPayloadC2S;
import net.fabricmc.fabric.impl.networking.CommonRegisterPayload;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.fabricmc.fabric.impl.recipe.ingredient.CustomIngredientPayloadC2S;
import net.fabricmc.fabric.impl.recipe.sync.SupportedRecipeSerializersPayloadC2S;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rebuilds a payload with the identifiers the policy withholds removed.
 *
 * <p>A byte-level rewrap is not possible here. Encoding resolves a codec by channel id and
 * casts the payload to that codec's type, so a foreign payload class sent under an
 * existing id fails on the cast. The only way to rewrite is to construct another instance
 * of the same record -- which means importing Fabric's unstable impl types.
 *
 * <p>That dependency is confined to this file on purpose. The Fabric API version is pinned
 * in gradle.properties, so a bump that reshapes these records breaks the build here rather
 * than shipping a jar that quietly stops filtering.
 *
 * <p>Non-identifier fields are copied through untouched. A payload matching none of these
 * types is returned unchanged: unfamiliar payloads are passed or dropped whole, never
 * partially rewritten.
 */
public final class PayloadRewriter {
    private PayloadRewriter() {
    }

    public static CustomPacketPayload rewrite(CustomPacketPayload payload, ExposurePolicy policy) {
        if (payload instanceof RegistrationPayload registration) {
            return new RegistrationPayload(registration.id(), keep(registration.channels(), policy));
        }
        if (payload instanceof CommonRegisterPayload common) {
            return new CommonRegisterPayload(
                    common.version(), common.phase(), keep(common.channels(), policy));
        }
        if (payload instanceof AcceptedAttachmentsPayloadC2S attachments) {
            return new AcceptedAttachmentsPayloadC2S(keep(attachments.acceptedAttachments(), policy));
        }
        if (payload instanceof SupportedRecipeSerializersPayloadC2S serializers) {
            return new SupportedRecipeSerializersPayloadC2S(
                    keep(serializers.synchronizedSerializers(), policy));
        }
        if (payload instanceof CustomIngredientPayloadC2S ingredients) {
            return new CustomIngredientPayloadC2S(
                    ingredients.protocolVersion(), keep(ingredients.registeredSerializers(), policy));
        }
        return payload;
    }

    /** True when this payload carries identifiers the policy may need to strip. */
    public static boolean isRewritable(CustomPacketPayload payload) {
        return payload instanceof RegistrationPayload
                || payload instanceof CommonRegisterPayload
                || payload instanceof AcceptedAttachmentsPayloadC2S
                || payload instanceof SupportedRecipeSerializersPayloadC2S
                || payload instanceof CustomIngredientPayloadC2S;
    }

    private static List<Identifier> keep(List<Identifier> input, ExposurePolicy policy) {
        List<String> kept = IdentifierFilter.retain(input.stream().map(Identifier::toString).toList(), policy);
        return kept.stream().map(Identifier::parse).toList();
    }

    private static Set<Identifier> keep(Set<Identifier> input, ExposurePolicy policy) {
        Set<String> asStrings = new LinkedHashSet<>();
        for (Identifier id : input) {
            asStrings.add(id.toString());
        }
        Set<Identifier> kept = new LinkedHashSet<>();
        for (String id : IdentifierFilter.retain(asStrings, policy)) {
            kept.add(Identifier.parse(id));
        }
        return kept;
    }
}
