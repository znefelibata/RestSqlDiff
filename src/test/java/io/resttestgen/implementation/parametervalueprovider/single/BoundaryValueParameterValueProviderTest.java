package io.resttestgen.implementation.parametervalueprovider.single;

import io.resttestgen.boot.ApiUnderTest;
import io.resttestgen.boot.Starter;
import io.resttestgen.core.Environment;
import io.resttestgen.core.datatype.parameter.attributes.ParameterType;
import io.resttestgen.core.datatype.parameter.attributes.ParameterTypeFormat;
import io.resttestgen.core.datatype.parameter.leaves.BooleanParameter;
import io.resttestgen.core.datatype.parameter.leaves.NumberParameter;
import io.resttestgen.core.datatype.parameter.leaves.StringParameter;
import io.resttestgen.core.openapi.CannotParseOpenApiException;
import io.resttestgen.core.testing.parametervalueprovider.ParameterValueProviderCachedFactory;
import io.resttestgen.core.testing.parametervalueprovider.ValueNotAvailableException;
import io.resttestgen.implementation.parametervalueprovider.ParameterValueProviderType;
import kotlin.Pair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BoundaryValueParameterValueProviderTest {

    private static BoundaryValueParameterValueProvider provider;

    @BeforeAll
    static void setUp() throws CannotParseOpenApiException, IOException, InterruptedException,
            InvocationTargetException, NoSuchMethodException, InstantiationException, IllegalAccessException {
        Starter.initEnvironment(ApiUnderTest.loadApiFromFile("petstore"));
        provider = (BoundaryValueParameterValueProvider)
                ParameterValueProviderCachedFactory.getParameterValueProvider(ParameterValueProviderType.BOUNDARY_VALUE);
    }

    @Test
    void provideValueForIntegerWithBounds() throws ValueNotAvailableException {
        NumberParameter param = new NumberParameter();
        param.setType(ParameterType.INTEGER);
        param.setMinimum(0.0);
        param.setMaximum(100.0);

        Set<Number> values = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            Pair<?, Object> result = provider.provideValueFor(param);
            assertNotNull(result.getSecond());
            assertTrue(result.getSecond() instanceof Number);
            values.add((Number) result.getSecond());
        }

        // Should have generated various values including boundaries
        assertTrue(values.size() > 1, "Should generate diverse values");
    }

    @Test
    void provideValueForIntegerWithoutBounds() throws ValueNotAvailableException {
        NumberParameter param = new NumberParameter();
        param.setType(ParameterType.INTEGER);

        for (int i = 0; i < 20; i++) {
            Pair<?, Object> result = provider.provideValueFor(param);
            assertNotNull(result.getSecond());
            assertTrue(result.getSecond() instanceof Number);
        }
    }

    @Test
    void provideValueForDoubleWithBounds() throws ValueNotAvailableException {
        NumberParameter param = new NumberParameter();
        param.setType(ParameterType.NUMBER);
        param.setMinimum(-50.5);
        param.setMaximum(50.5);

        Set<Double> values = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            Pair<?, Object> result = provider.provideValueFor(param);
            assertNotNull(result.getSecond());
            assertTrue(result.getSecond() instanceof Number);
            values.add(((Number) result.getSecond()).doubleValue());
        }

        assertTrue(values.size() > 1, "Should generate diverse double values");
    }

    @Test
    void provideValueForExclusiveMinimum() throws ValueNotAvailableException {
        NumberParameter param = new NumberParameter();
        param.setType(ParameterType.INTEGER);
        param.setMinimum(0.0);
        param.setMaximum(10.0);
        param.setExclusiveMinimum(true);

        // With exclusive minimum, values should typically be > 0
        for (int i = 0; i < 50; i++) {
            Pair<?, Object> result = provider.provideValueFor(param);
            assertNotNull(result.getSecond());
        }
    }

    @Test
    void provideValueForExclusiveMaximum() throws ValueNotAvailableException {
        NumberParameter param = new NumberParameter();
        param.setType(ParameterType.INTEGER);
        param.setMinimum(0.0);
        param.setMaximum(10.0);
        param.setExclusiveMaximum(true);

        for (int i = 0; i < 50; i++) {
            Pair<?, Object> result = provider.provideValueFor(param);
            assertNotNull(result.getSecond());
        }
    }

    @Test
    void provideValueForBoolean() throws ValueNotAvailableException {
        BooleanParameter param = new BooleanParameter();

        Set<Boolean> values = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            Pair<?, Object> result = provider.provideValueFor(param);
            assertNotNull(result.getSecond());
            assertTrue(result.getSecond() instanceof Boolean);
            values.add((Boolean) result.getSecond());
        }

        // Should generate both true and false
        assertEquals(2, values.size(), "Should generate both true and false");
    }

    @Test
    void provideValueForStringWithLengthConstraints() throws ValueNotAvailableException {
        StringParameter param = new StringParameter();
        param.setMinLength(5);
        param.setMaxLength(20);

        Set<Integer> lengths = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            Pair<?, Object> result = provider.provideValueFor(param);
            assertNotNull(result.getSecond());
            assertTrue(result.getSecond() instanceof String);
            String str = (String) result.getSecond();
            lengths.add(str.length());
        }

        // Should generate strings of various lengths
        assertTrue(lengths.size() > 1, "Should generate strings of different lengths");
    }

    @Test
    void provideValueForStringWithMinLengthOnly() throws ValueNotAvailableException {
        StringParameter param = new StringParameter();
        param.setMinLength(10);

        for (int i = 0; i < 20; i++) {
            Pair<?, Object> result = provider.provideValueFor(param);
            assertNotNull(result.getSecond());
            assertTrue(result.getSecond() instanceof String);
        }
    }

    @Test
    void provideValueForStringWithMaxLengthOnly() throws ValueNotAvailableException {
        StringParameter param = new StringParameter();
        param.setMaxLength(50);

        for (int i = 0; i < 20; i++) {
            Pair<?, Object> result = provider.provideValueFor(param);
            assertNotNull(result.getSecond());
            assertTrue(result.getSecond() instanceof String);
        }
    }

    @RepeatedTest(10)
    void generatesBoundaryValuesWithConfiguredProbability() throws ValueNotAvailableException {
        // Use the already-initialized provider but reconfigure it
        provider.setBoundaryProbability(100); // Always use exact boundary
        provider.setJustInsideProbability(0);
        provider.setJustOutsideProbability(0);
        provider.setInRangeProbability(0);

        NumberParameter param = new NumberParameter();
        param.setType(ParameterType.INTEGER);
        param.setMinimum(10.0);
        param.setMaximum(20.0);

        Set<Integer> values = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            Pair<?, Object> result = provider.provideValueFor(param);
            int value = ((Number) result.getSecond()).intValue();
            values.add(value);
        }

        // With 100% boundary probability, should only generate 10 or 20
        assertTrue(values.contains(10) || values.contains(20),
                "Should generate boundary values 10 or 20");
        for (Integer value : values) {
            assertTrue(value == 10 || value == 20,
                    "All values should be boundaries (10 or 20), got: " + value);
        }

        // Reset to default probabilities
        provider.setBoundaryProbability(30);
        provider.setJustInsideProbability(30);
        provider.setJustOutsideProbability(20);
        provider.setInRangeProbability(20);
    }

    @Test
    void configuredJustOutsideProbabilityGeneratesOutOfBoundsValues() throws ValueNotAvailableException {
        // Configure provider for just outside boundary testing
        provider.setBoundaryProbability(0);
        provider.setJustInsideProbability(0);
        provider.setJustOutsideProbability(100); // Always use just outside
        provider.setInRangeProbability(0);

        NumberParameter param = new NumberParameter();
        param.setType(ParameterType.INTEGER);
        param.setMinimum(10.0);
        param.setMaximum(20.0);

        boolean hasValueBelow = false;
        boolean hasValueAbove = false;
        for (int i = 0; i < 100; i++) {
            Pair<?, Object> result = provider.provideValueFor(param);
            int value = ((Number) result.getSecond()).intValue();
            if (value < 10) hasValueBelow = true;
            if (value > 20) hasValueAbove = true;
        }

        // Should generate values outside the bounds
        assertTrue(hasValueBelow || hasValueAbove,
                "Should generate at least some values outside bounds");

        // Reset to default probabilities
        provider.setBoundaryProbability(30);
        provider.setJustInsideProbability(30);
        provider.setJustOutsideProbability(20);
        provider.setInRangeProbability(20);
    }

    @Test
    void probabilityGettersAndSetters() {
        // Save current values
        int originalBoundary = provider.getBoundaryProbability();
        int originalJustInside = provider.getJustInsideProbability();
        int originalJustOutside = provider.getJustOutsideProbability();
        int originalInRange = provider.getInRangeProbability();

        provider.setBoundaryProbability(25);
        provider.setJustInsideProbability(25);
        provider.setJustOutsideProbability(25);
        provider.setInRangeProbability(25);

        assertEquals(25, provider.getBoundaryProbability());
        assertEquals(25, provider.getJustInsideProbability());
        assertEquals(25, provider.getJustOutsideProbability());
        assertEquals(25, provider.getInRangeProbability());

        // Restore original values
        provider.setBoundaryProbability(originalBoundary);
        provider.setJustInsideProbability(originalJustInside);
        provider.setJustOutsideProbability(originalJustOutside);
        provider.setInRangeProbability(originalInRange);
    }

    @Test
    void handlesSwappedMinMax() throws ValueNotAvailableException {
        NumberParameter param = new NumberParameter();
        param.setType(ParameterType.INTEGER);
        param.setMinimum(100.0); // min > max (swapped)
        param.setMaximum(10.0);

        // Should still generate values without error
        for (int i = 0; i < 20; i++) {
            Pair<?, Object> result = provider.provideValueFor(param);
            assertNotNull(result.getSecond());
        }
    }

    @Test
    void returnsCorrectProviderInPair() throws ValueNotAvailableException {
        NumberParameter param = new NumberParameter();
        param.setType(ParameterType.INTEGER);

        Pair<?, Object> result = provider.provideValueFor(param);
        assertTrue(result.getFirst() instanceof BoundaryValueParameterValueProvider);
    }
}

