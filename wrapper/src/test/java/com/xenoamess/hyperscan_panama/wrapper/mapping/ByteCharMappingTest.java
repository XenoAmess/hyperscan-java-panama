package com.xenoamess.hyperscan_panama.wrapper.mapping;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ByteCharMappingTest {

    @Test
    void create_selectsByteMappingForSmallIndices() {
        ByteCharMapping mapping = ByteCharMapping.create(10, 255);
        assertThat(mapping).isInstanceOf(ByteMapping.class);
        assertThat(mapping.getMappingSize()).isEqualTo(10);
    }

    @Test
    void create_selectsShortMappingForMediumIndices() {
        ByteCharMapping mapping = ByteCharMapping.create(10, 256);
        assertThat(mapping).isInstanceOf(ShortMapping.class);
    }

    @Test
    void create_selectsIntMappingForLargeIndices() {
        ByteCharMapping mapping = ByteCharMapping.create(10, 65536);
        assertThat(mapping).isInstanceOf(IntMapping.class);
    }

    @Test
    void create_rejectsNegativeMaxCharIndex() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ByteCharMapping.create(10, -1));
        assertThat(exception).hasMessageContaining("Character limit can't be negative");
    }

    @Test
    void byteMapping_rejectsOutOfBoundsCharIndex() {
        ByteCharMapping mapping = new ByteMapping(10);
        assertThrows(IllegalArgumentException.class, () -> mapping.setCharIndex(0, 256));
        assertThrows(IllegalArgumentException.class, () -> mapping.setCharIndex(0, -1));
    }

    @Test
    void byteMapping_roundTrip() {
        ByteCharMapping mapping = new ByteMapping(10);
        mapping.setCharIndex(0, 0);
        mapping.setCharIndex(1, 255);
        assertThat(mapping.getCharIndex(0)).isEqualTo(0);
        assertThat(mapping.getCharIndex(1)).isEqualTo(255);
    }

    @Test
    void shortMapping_rejectsOutOfBoundsCharIndex() {
        ByteCharMapping mapping = new ShortMapping(10);
        assertThrows(IllegalArgumentException.class, () -> mapping.setCharIndex(0, 65536));
        assertThrows(IllegalArgumentException.class, () -> mapping.setCharIndex(0, -1));
    }

    @Test
    void shortMapping_roundTrip() {
        ByteCharMapping mapping = new ShortMapping(10);
        mapping.setCharIndex(0, 256);
        mapping.setCharIndex(1, 65535);
        assertThat(mapping.getCharIndex(0)).isEqualTo(256);
        assertThat(mapping.getCharIndex(1)).isEqualTo(65535);
    }

    @Test
    void intMapping_rejectsNegativeCharIndex() {
        ByteCharMapping mapping = new IntMapping(10);
        assertThrows(IllegalArgumentException.class, () -> mapping.setCharIndex(0, -1));
    }

    @Test
    void intMapping_roundTripForLargeIndices() {
        ByteCharMapping mapping = new IntMapping(10);
        mapping.setCharIndex(0, 100000);
        mapping.setCharIndex(1, 70000);
        assertThat(mapping.getCharIndex(0)).isEqualTo(100000);
        assertThat(mapping.getCharIndex(1)).isEqualTo(70000);
    }
}
