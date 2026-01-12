package io.resttestgen.implementation.parametervalueprovider.single;

import io.resttestgen.core.Environment;
import io.resttestgen.core.datatype.parameter.attributes.ParameterTypeFormat;
import io.resttestgen.core.datatype.parameter.leaves.*;
import io.resttestgen.core.helper.ExtendedRandom;
import io.resttestgen.core.testing.parametervalueprovider.ParameterValueProvider;
import kotlin.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Generates boundary values for testing edge cases of parameters.
 * This provider implements boundary value analysis (BVA) testing strategy:
 * - Boundary values (min, max)
 * - Just inside boundary (min+1, max-1)
 * - Just outside boundary (min-1, max+1)
 * - In-range values (within valid boundaries)
 *
 * Supported parameter types:
 * - NumberParameter: Uses type-specific min/max (INT8, INT16, INT32, INT64, FLOAT, DOUBLE, etc.)
 * - BooleanParameter: Randomly returns true or false
 * - StringParameter: Generates strings at length boundaries
 * - GenericParameter: Treated as StringParameter
 * - NullParameter: Returns null
 *
 * Probability distribution is configurable:
 * - Default: 30% boundary, 30% just inside, 20% just outside, 20% in-range
 */
public class BoundaryValueParameterValueProvider extends ParameterValueProvider {

    private static final Logger logger = LogManager.getLogger(BoundaryValueParameterValueProvider.class);
    private static final ExtendedRandom random = Environment.getInstance().getRandom();

    // Probability configuration (should sum to 100)
    private int boundaryProbability = 30;      // Probability to select exact boundary values (min or max)
    private int justInsideProbability = 30;    // Probability to select just inside boundary (min+1 or max-1)
    private int justOutsideProbability = 20;   // Probability to select just outside boundary (min-1 or max+1)
    private int inRangeProbability = 20;       // Probability to select random in-range value

    /**
     * Boundary value type enumeration
     */
    public enum BoundaryType {
        EXACT_BOUNDARY,     // min or max
        JUST_INSIDE,        // min+1 or max-1
        JUST_OUTSIDE,       // min-1 or max+1 (invalid values)
        IN_RANGE            // random value within range
    }

    @Override
    public Pair<ParameterValueProvider, Object> provideValueFor(LeafParameter leafParameter) {
        return new Pair<>(this, generateValueFor(leafParameter));
    }

    private Object generateValueFor(LeafParameter leafParameter) {
        if (leafParameter instanceof NumberParameter) {
            return generateBoundaryNumber((NumberParameter) leafParameter);
        } else if (leafParameter instanceof BooleanParameter) {
            return generateBoundaryBoolean();
        } else if (leafParameter instanceof StringParameter) {
            return generateBoundaryString((StringParameter) leafParameter);
        } else if (leafParameter instanceof GenericParameter) {
            // GenericParameter is treated as String type
            return generateBoundaryStringForGeneric();
        } else if (leafParameter instanceof NullParameter) {
            return null;
        } else {
            // Fallback: generate a random boundary-like value
            logger.debug("Unknown parameter type for boundary generation: {}, using random fallback",
                    leafParameter.getClass().getSimpleName());
            return random.nextInt(-1000, 1001);  // Small range boundary-like values
        }
    }

    /**
     * Generates boundary string for GenericParameter (no length constraints available).
     * Returns common boundary test strings for API testing.
     */
    private String generateBoundaryStringForGeneric() {
        BoundaryType selectedType = selectBoundaryType();

        switch (selectedType) {
            case EXACT_BOUNDARY:
                // Test empty string or very short string
                return random.nextBoolean() ? "" : "a";
            case JUST_INSIDE:
                // Short practical strings
                return random.nextString(random.nextInt(1, 10));
            case JUST_OUTSIDE:
                // Very long string to test limits
                return random.nextString(random.nextInt(500, 1001));
            case IN_RANGE:
            default:
                // Normal length string
                return random.nextString(random.nextInt(5, 50));
        }
    }

    /**
     * Generates boundary values for numeric parameters.
     */
    private Number generateBoundaryNumber(NumberParameter parameter) {
        ParameterTypeFormat format = parameter.inferFormat();
        BoundaryType selectedType = selectBoundaryType();

        // Determine bounds based on format and parameter constraints
        double typeMin = getTypeMinimum(format);
        double typeMax = getTypeMaximum(format);

        double effectiveMin = parameter.getMinimum() != null ? parameter.getMinimum() : typeMin;
        double effectiveMax = parameter.getMaximum() != null ? parameter.getMaximum() : typeMax;

        // Handle exclusive boundaries
        if (parameter.isExclusiveMinimum() && parameter.getMinimum() != null) {
            effectiveMin = effectiveMin + getMinimalStep(format);
        }
        if (parameter.isExclusiveMaximum() && parameter.getMaximum() != null) {
            effectiveMax = effectiveMax - getMinimalStep(format);
        }

        // Ensure min <= max
        if (effectiveMin > effectiveMax) {
            double temp = effectiveMin;
            effectiveMin = effectiveMax;
            effectiveMax = temp;
        }

        double value;
        switch (selectedType) {
            case EXACT_BOUNDARY:
                // Select either min or max boundary
                value = random.nextBoolean() ? effectiveMin : effectiveMax;
                break;
            case JUST_INSIDE:
                // Select value just inside the boundary
                if (random.nextBoolean()) {
                    value = effectiveMin + getMinimalStep(format);
                } else {
                    value = effectiveMax - getMinimalStep(format);
                }
                // Clamp to valid range
                value = Math.max(effectiveMin, Math.min(effectiveMax, value));
                break;
            case JUST_OUTSIDE:
                // Select value just outside the boundary (potentially invalid)
                if (random.nextBoolean()) {
                    value = effectiveMin - getMinimalStep(format);
                } else {
                    value = effectiveMax + getMinimalStep(format);
                }
                break;
            case IN_RANGE:
            default:
                // Generate random value within range
                value = random.nextDouble(effectiveMin, effectiveMax);
                break;
        }

        // Convert to appropriate type based on format
        return convertToFormat(value, format);
    }

    /**
     * Generates boundary values for boolean parameters.
     * For boolean, we can test: true, false
     */
    private Boolean generateBoundaryBoolean() {
        // Boolean has only two values, so we just randomly pick one
        // But with slight bias towards the less common value in testing
        return random.nextBoolean();
    }

    /**
     * Generates boundary values for string parameters based on length constraints.
     * When no constraints are specified, uses practical defaults for API testing:
     * - Default min: 0 (empty string)
     * - Default max: 100 (reasonable for most API fields)
     *
     * Special cases:
     * - If only minLength is specified, max = min + 50
     * - If only maxLength is specified, min = 0
     * - If neither specified, uses [0, 100] as practical bounds
     */
    private String generateBoundaryString(StringParameter parameter) {
        BoundaryType selectedType = selectBoundaryType();

        Integer minLength = parameter.getMinLength();
        Integer maxLength = parameter.getMaxLength();

        // Determine effective bounds with practical defaults for API testing
        int effectiveMin;
        int effectiveMax;

        if (minLength != null && maxLength != null) {
            // Both specified - use them
            effectiveMin = minLength;
            effectiveMax = maxLength;
        } else if (minLength != null) {
            // Only min specified - max is min + reasonable range
            effectiveMin = minLength;
            effectiveMax = minLength + 50;
        } else if (maxLength != null) {
            // Only max specified - min is 0
            effectiveMin = 0;
            effectiveMax = maxLength;
        } else {
            // Neither specified - use practical defaults for API testing
            // Common string field lengths: empty, short (1-10), medium (10-50), long (50-100)
            effectiveMin = 0;
            effectiveMax = 100;
        }

        // Ensure min <= max
        if (effectiveMin > effectiveMax) {
            int temp = effectiveMin;
            effectiveMin = effectiveMax;
            effectiveMax = temp;
        }

        int targetLength;
        switch (selectedType) {
            case EXACT_BOUNDARY:
                // Generate string of exact boundary length
                targetLength = random.nextBoolean() ? effectiveMin : effectiveMax;
                break;
            case JUST_INSIDE:
                // Just inside boundary
                if (random.nextBoolean()) {
                    targetLength = Math.min(effectiveMax, effectiveMin + 1);
                } else {
                    targetLength = Math.max(effectiveMin, effectiveMax - 1);
                }
                break;
            case JUST_OUTSIDE:
                // Just outside boundary (potentially invalid)
                if (random.nextBoolean() && effectiveMin > 0) {
                    targetLength = effectiveMin - 1;
                } else {
                    targetLength = effectiveMax + 1;
                }
                break;
            case IN_RANGE:
            default:
                // Random length within range
                targetLength = random.nextInt(effectiveMin, effectiveMax + 1);
                break;
        }

        // Generate string of target length
        return random.nextString(Math.max(0, targetLength));
    }

    /**
     * Selects a boundary type based on configured probabilities.
     */
    private BoundaryType selectBoundaryType() {
        int roll = random.nextInt(100);
        int cumulative = 0;

        cumulative += boundaryProbability;
        if (roll < cumulative) {
            return BoundaryType.EXACT_BOUNDARY;
        }

        cumulative += justInsideProbability;
        if (roll < cumulative) {
            return BoundaryType.JUST_INSIDE;
        }

        cumulative += justOutsideProbability;
        if (roll < cumulative) {
            return BoundaryType.JUST_OUTSIDE;
        }

        return BoundaryType.IN_RANGE;
    }

    /**
     * Gets the minimum value for a given numeric format.
     */
    private double getTypeMinimum(ParameterTypeFormat format) {
        switch (format) {
            case INT8:
                return -128;
            case INT16:
                return -32768;
            case INT32:
                return Integer.MIN_VALUE;
            case INT64:
                return Long.MIN_VALUE;
            case UINT8:
            case UINT16:
            case UINT32:
            case UINT64:
                return 0;
            case FLOAT:
                return -Float.MAX_VALUE;
            case DOUBLE:
                return -Double.MAX_VALUE;
            default:
                return Integer.MIN_VALUE;
        }
    }

    /**
     * Gets the maximum value for a given numeric format.
     */
    private double getTypeMaximum(ParameterTypeFormat format) {
        switch (format) {
            case INT8:
                return 127;
            case INT16:
                return 32767;
            case INT32:
                return Integer.MAX_VALUE;
            case INT64:
                return Long.MAX_VALUE;
            case UINT8:
                return 255;
            case UINT16:
                return 65535;
            case UINT32:
                return 4294967295L;
            case UINT64:
                return Long.MAX_VALUE; // Approximation for unsigned long
            case FLOAT:
                return Float.MAX_VALUE;
            case DOUBLE:
                return Double.MAX_VALUE;
            default:
                return Integer.MAX_VALUE;
        }
    }

    /**
     * Gets the minimal step for boundary adjustments based on format.
     */
    private double getMinimalStep(ParameterTypeFormat format) {
        switch (format) {
            case FLOAT:
                return Float.MIN_VALUE * 1000; // Small but visible step
            case DOUBLE:
                return Double.MIN_VALUE * 1000;
            default:
                return 1.0; // Integer types use step of 1
        }
    }

    /**
     * Converts a double value to the appropriate Number type based on format.
     */
    private Number convertToFormat(double value, ParameterTypeFormat format) {
        switch (format) {
            case INT8:
            case INT16:
            case INT32:
            case UINT8:
            case UINT16:
                return (int) Math.round(value);
            case INT64:
            case UINT32:
            case UINT64:
                return Math.round(value);
            case FLOAT:
                return (float) value;
            case DOUBLE:
            default:
                return value;
        }
    }

    // Configuration setters for probability customization

    public void setBoundaryProbability(int probability) {
        this.boundaryProbability = probability;
    }

    public void setJustInsideProbability(int probability) {
        this.justInsideProbability = probability;
    }

    public void setJustOutsideProbability(int probability) {
        this.justOutsideProbability = probability;
    }

    public void setInRangeProbability(int probability) {
        this.inRangeProbability = probability;
    }

    public int getBoundaryProbability() {
        return boundaryProbability;
    }

    public int getJustInsideProbability() {
        return justInsideProbability;
    }

    public int getJustOutsideProbability() {
        return justOutsideProbability;
    }

    public int getInRangeProbability() {
        return inRangeProbability;
    }
}

