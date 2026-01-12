package io.resttestgen.implementation.parametervalueprovider.multi;

import io.resttestgen.core.Environment;
import io.resttestgen.core.datatype.parameter.leaves.LeafParameter;
import io.resttestgen.core.datatype.parameter.leaves.NumberParameter;
import io.resttestgen.core.datatype.parameter.leaves.BooleanParameter;
import io.resttestgen.core.datatype.parameter.leaves.StringParameter;
import io.resttestgen.core.helper.ExtendedRandom;
import io.resttestgen.core.testing.parametervalueprovider.CountableParameterValueProvider;
import io.resttestgen.core.testing.parametervalueprovider.ParameterValueProvider;
import io.resttestgen.core.testing.parametervalueprovider.ParameterValueProviderCachedFactory;
import io.resttestgen.core.testing.parametervalueprovider.ValueNotAvailableException;
import io.resttestgen.implementation.parametervalueprovider.ParameterValueProviderType;
import io.resttestgen.implementation.parametervalueprovider.single.*;
import kotlin.Pair;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A combined parameter value provider that integrates boundary value analysis with other strategies.
 *
 * For primitive types (int, double, boolean, string with length constraints):
 * - Uses configurable probability to select boundary values
 * - Falls back to other strategies (enum, examples, dictionary, random) when not using boundary values
 *
 * Probability distribution (configurable):
 * - Default: 40% boundary value testing, 60% other strategies
 * - For non-primitive types: delegates to EnumAndExamplePriorityParameterValueProvider
 */
public class BoundaryValuePriorityParameterValueProvider extends ParameterValueProvider {

    private final ExtendedRandom random = Environment.getInstance().getRandom();

    // Probability to use boundary value testing for primitive types (0-100)
    private int boundaryValueProbability = 40;

    @Override
    public Pair<ParameterValueProvider, Object> provideValueFor(LeafParameter leafParameter) throws ValueNotAvailableException {

        // Check if the parameter is a primitive type that benefits from boundary testing
        boolean isPrimitiveType = leafParameter instanceof NumberParameter
                || leafParameter instanceof BooleanParameter
                || (leafParameter instanceof StringParameter && hasLengthConstraints((StringParameter) leafParameter));

        // For primitive types, use boundary value testing with configured probability
        if (isPrimitiveType && random.nextInt(100) < boundaryValueProbability) {
            BoundaryValueParameterValueProvider boundaryProvider =
                    (BoundaryValueParameterValueProvider) ParameterValueProviderCachedFactory.getParameterValueProvider(ParameterValueProviderType.BOUNDARY_VALUE);
            return boundaryProvider.provideValueFor(leafParameter);
        }

        // Otherwise, use the existing EnumAndExamplePriority strategy
        return fallbackToOtherStrategies(leafParameter);
    }

    /**
     * Checks if a string parameter has length constraints that make boundary testing meaningful.
     */
    private boolean hasLengthConstraints(StringParameter parameter) {
        return parameter.getMinLength() != null || parameter.getMaxLength() != null;
    }

    /**
     * Falls back to other value generation strategies.
     */
    private Pair<ParameterValueProvider, Object> fallbackToOtherStrategies(LeafParameter leafParameter) throws ValueNotAvailableException {
        // Try enum and example values first with high priority
        EnumParameterValueProvider enumProvider = (EnumParameterValueProvider)
                ParameterValueProviderCachedFactory.getParameterValueProvider(ParameterValueProviderType.ENUM);
        enumProvider.setStrict(this.strict);

        ExamplesParameterValueProvider examplesProvider = (ExamplesParameterValueProvider)
                ParameterValueProviderCachedFactory.getParameterValueProvider(ParameterValueProviderType.EXAMPLES);
        examplesProvider.setStrict(this.strict);

        int numEnums = enumProvider.countAvailableValuesFor(leafParameter);
        int numExamples = examplesProvider.countAvailableValuesFor(leafParameter);

        // 80% chance to use enum/example if available
        if (numEnums + numExamples > 0 && random.nextInt(10) < 8) {
            if (random.nextInt(numEnums + numExamples) < numEnums) {
                return enumProvider.provideValueFor(leafParameter);
            } else {
                return examplesProvider.provideValueFor(leafParameter);
            }
        }

        // Otherwise, use other available providers
        Set<ParameterValueProvider> providers = new HashSet<>();

        // Random providers are always available
        providers.add(ParameterValueProviderCachedFactory.getParameterValueProvider(ParameterValueProviderType.RANDOM));
        providers.add(ParameterValueProviderCachedFactory.getParameterValueProvider(ParameterValueProviderType.NARROW_RANDOM));

        // List of candidate providers that will be used only if they have values available
        Set<CountableParameterValueProvider> candidateProviders = new HashSet<>();
        candidateProviders.add((DefaultParameterValueProvider)
                ParameterValueProviderCachedFactory.getParameterValueProvider(ParameterValueProviderType.DEFAULT));
        candidateProviders.add((ResponseDictionaryParameterValueProvider)
                ParameterValueProviderCachedFactory.getParameterValueProvider(ParameterValueProviderType.RESPONSE_DICTIONARY));
        candidateProviders.add((LastResponseDictionaryParameterValueProvider)
                ParameterValueProviderCachedFactory.getParameterValueProvider(ParameterValueProviderType.LAST_RESPONSE_DICTIONARY));

        candidateProviders.forEach(p -> p.setStrict(this.strict));

        providers.addAll(candidateProviders.stream()
                .filter(p -> p.countAvailableValuesFor(leafParameter) > 0)
                .collect(Collectors.toSet()));

        ParameterValueProvider chosenProvider = random.nextElement(providers).get();
        return chosenProvider.provideValueFor(leafParameter);
    }

    /**
     * Sets the probability of using boundary value testing for primitive types.
     * @param probability value between 0 and 100
     */
    public void setBoundaryValueProbability(int probability) {
        if (probability < 0 || probability > 100) {
            throw new IllegalArgumentException("Probability must be between 0 and 100");
        }
        this.boundaryValueProbability = probability;
    }

    public int getBoundaryValueProbability() {
        return boundaryValueProbability;
    }
}

