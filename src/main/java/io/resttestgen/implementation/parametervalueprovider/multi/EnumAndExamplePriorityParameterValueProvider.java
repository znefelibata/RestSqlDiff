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

import java.util.ArrayList;
import java.util.List;

/**
 * Parameter value provider with weighted probability distribution:
 * - 60% probability for enum/example values (if available)
 * - For remaining 40%, use weighted selection among:
 *   - Boundary value (high priority for primitive types)
 *   - Response dictionary (high priority if available)
 *   - Last response dictionary (high priority if available)
 *   - Random/NarrowRandom (lower priority)
 *   - Default (lower priority)
 */
public class EnumAndExamplePriorityParameterValueProvider extends ParameterValueProvider {

    final ExtendedRandom random = Environment.getInstance().getRandom();

    // Probability configuration (percentage)
    private static final int ENUM_EXAMPLE_PROBABILITY = 60;  // 60% for enum/example
    private static final int BOUNDARY_WEIGHT = 50;           // High weight for boundary values
    private static final int RESPONSE_DICT_WEIGHT = 40;      // High weight for response dictionary
    private static final int LAST_RESPONSE_DICT_WEIGHT = 20; // High weight for last response dictionary
    private static final int RANDOM_WEIGHT = 10;             // Lower weight for random
    private static final int NARROW_RANDOM_WEIGHT = 10;      // Lower weight for narrow random
    private static final int DEFAULT_WEIGHT = 5;             // Lowest weight for default

    @Override
    public Pair<ParameterValueProvider, Object> provideValueFor(LeafParameter leafParameter) throws ValueNotAvailableException {

        // Step 1: Try enum/example values with 60% probability
        EnumParameterValueProvider enumProvider = (EnumParameterValueProvider)
                ParameterValueProviderCachedFactory.getParameterValueProvider(ParameterValueProviderType.ENUM);
        enumProvider.setStrict(this.strict);

        ExamplesParameterValueProvider examplesProvider = (ExamplesParameterValueProvider)
                ParameterValueProviderCachedFactory.getParameterValueProvider(ParameterValueProviderType.EXAMPLES);
        examplesProvider.setStrict(this.strict);

        int numEnums = enumProvider.countAvailableValuesFor(leafParameter);
        int numExamples = examplesProvider.countAvailableValuesFor(leafParameter);

        // 60% probability to use enum/example if available
        if (numEnums + numExamples > 0 && random.nextInt(100) < ENUM_EXAMPLE_PROBABILITY) {
            if (random.nextInt(numEnums + numExamples) < numEnums) {
                return enumProvider.provideValueFor(leafParameter);
            } else {
                return examplesProvider.provideValueFor(leafParameter);
            }
        }

        // Step 2: Use weighted selection for other strategies
        return selectFromWeightedProviders(leafParameter);
    }

    /**
     * Selects a provider using weighted probability distribution.
     * Higher weights mean higher probability of being selected.
     */
    private Pair<ParameterValueProvider, Object> selectFromWeightedProviders(LeafParameter leafParameter)
            throws ValueNotAvailableException {

        List<WeightedProvider> weightedProviders = new ArrayList<>();

        // Boundary value provider - high priority for primitive types
        if (isPrimitiveType(leafParameter)) {
            ParameterValueProvider boundaryProvider =
                    ParameterValueProviderCachedFactory.getParameterValueProvider(ParameterValueProviderType.BOUNDARY_VALUE);
            weightedProviders.add(new WeightedProvider(boundaryProvider, BOUNDARY_WEIGHT));
        }

        // Response dictionary - high priority if has values
        ResponseDictionaryParameterValueProvider responseProvider = (ResponseDictionaryParameterValueProvider)
                ParameterValueProviderCachedFactory.getParameterValueProvider(ParameterValueProviderType.RESPONSE_DICTIONARY);
        responseProvider.setStrict(this.strict);
        if (responseProvider.countAvailableValuesFor(leafParameter) > 0) {
            weightedProviders.add(new WeightedProvider(responseProvider, RESPONSE_DICT_WEIGHT));
        }

        // Last response dictionary - high priority if has values
        LastResponseDictionaryParameterValueProvider lastResponseProvider = (LastResponseDictionaryParameterValueProvider)
                ParameterValueProviderCachedFactory.getParameterValueProvider(ParameterValueProviderType.LAST_RESPONSE_DICTIONARY);
        lastResponseProvider.setStrict(this.strict);
        if (lastResponseProvider.countAvailableValuesFor(leafParameter) > 0) {
            weightedProviders.add(new WeightedProvider(lastResponseProvider, LAST_RESPONSE_DICT_WEIGHT));
        }

        // Random provider - always available, lower priority
        ParameterValueProvider randomProvider =
                ParameterValueProviderCachedFactory.getParameterValueProvider(ParameterValueProviderType.RANDOM);
        weightedProviders.add(new WeightedProvider(randomProvider, RANDOM_WEIGHT));

        // Narrow random provider - always available, lower priority
        ParameterValueProvider narrowRandomProvider =
                ParameterValueProviderCachedFactory.getParameterValueProvider(ParameterValueProviderType.NARROW_RANDOM);
        weightedProviders.add(new WeightedProvider(narrowRandomProvider, NARROW_RANDOM_WEIGHT));

        // Default provider - lowest priority if has values
        DefaultParameterValueProvider defaultProvider = (DefaultParameterValueProvider)
                ParameterValueProviderCachedFactory.getParameterValueProvider(ParameterValueProviderType.DEFAULT);
        defaultProvider.setStrict(this.strict);
        if (defaultProvider.countAvailableValuesFor(leafParameter) > 0) {
            weightedProviders.add(new WeightedProvider(defaultProvider, DEFAULT_WEIGHT));
        }

        // Calculate total weight and select based on weighted probability
        int totalWeight = weightedProviders.stream().mapToInt(wp -> wp.weight).sum();
        int randomValue = random.nextInt(totalWeight);

        int cumulativeWeight = 0;
        for (WeightedProvider wp : weightedProviders) {
            cumulativeWeight += wp.weight;
            if (randomValue < cumulativeWeight) {
                return wp.provider.provideValueFor(leafParameter);
            }
        }

        // Fallback to random (should not reach here)
        return randomProvider.provideValueFor(leafParameter);
    }

    /**
     * Checks if the parameter is a primitive type that benefits from boundary testing.
     */
    private boolean isPrimitiveType(LeafParameter leafParameter) {
        if (leafParameter instanceof NumberParameter || leafParameter instanceof BooleanParameter) {
            return true;
        }
        if (leafParameter instanceof StringParameter) {
            StringParameter sp = (StringParameter) leafParameter;
            return sp.getMinLength() != null || sp.getMaxLength() != null;
        }
        return false;
    }

    /**
     * Helper class to hold provider with its weight.
     */
    private static class WeightedProvider {
        final ParameterValueProvider provider;
        final int weight;

        WeightedProvider(ParameterValueProvider provider, int weight) {
            this.provider = provider;
            this.weight = weight;
        }
    }
}
