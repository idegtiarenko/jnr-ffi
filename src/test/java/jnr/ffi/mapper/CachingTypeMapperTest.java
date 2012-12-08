package jnr.ffi.mapper;


import jnr.ffi.Pointer;
import jnr.ffi.annotations.In;
import jnr.ffi.annotations.Out;
import jnr.ffi.provider.converters.EnumSetConverter;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;


public class CachingTypeMapperTest {
    public static interface Lib {
        public static enum Enum1 {
            A, B;
        }
        public static enum Enum2 {
            A, B;
        }
        public int ret_int();
        public Set<Enum1> ret_enumset1a();
        public Set<Enum1> ret_enumset1b();
        public Set<Enum2> ret_enumset2a();
        public Set<Enum2> ret_enumset2b();
        public Set<Integer> ret_intset();
        public void enumset_param(Set<Enum1> enums);
        public void annotated_params(@In long[] in, @Out long[] out);
        public void int_array_params(int[] in, int[] out);
        public void intset_param(Set<Integer> bitfield);
    }

    private static Method getLibMethod(String name, Class... parameterTypes) {
        try {
            return Lib.class.getMethod(name, parameterTypes);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @ToNativeConverter.Cacheable
    private static final class LongArrayParameterConverter implements ToNativeConverter<long[], Pointer> {
        @Override
        public Pointer toNative(long[] value, ToNativeContext context) {
            return null;
        }

        @Override
        public Class<Pointer> nativeType() {
            return Pointer.class;
        }
    }

    // Do not mark as cacheable
    private static final class IntArrayParameterConverter implements ToNativeConverter<int[], Pointer> {
        @Override
        public Pointer toNative(int[] value, ToNativeContext context) {
            return null;
        }

        @Override
        public Class<Pointer> nativeType() {
            return Pointer.class;
        }
    }

    private static final class TestTypeMapper implements SignatureTypeMapper {
        @Override
        public FromNativeConverter getFromNativeConverter(SignatureType type, FromNativeContext context) {
            FromNativeConverter converter;
            if (type.getDeclaredType() == Set.class && (converter = EnumSetConverter.getFromNativeConverter(type, context)) != null) {
                 return converter;
            } else {
                return null;
            }
        }

        @Override
        public ToNativeConverter getToNativeConverter(SignatureType type, ToNativeContext context) {
            ToNativeConverter converter;
            if (type.getDeclaredType() == Set.class && (converter = EnumSetConverter.getToNativeConverter(type, context)) != null) {
                return converter;

            } else if (long[].class == type.getDeclaredType()) {
                return new LongArrayParameterConverter();

            } else if (int[].class == type.getDeclaredType()) {
                return new IntArrayParameterConverter();

            } else {
                return null;
            }
        }
    }

    private static final class CountingTypeMapper implements SignatureTypeMapper {
        private final Map<Class, Integer> fromConverterStats = new HashMap<Class, Integer>();
        private final Map<Class, Integer> toConverterStats = new HashMap<Class, Integer>();
        private final SignatureTypeMapper typeMapper;

        CountingTypeMapper(SignatureTypeMapper typeMapper) {
            this.typeMapper = typeMapper;
        }

        @Override
        public FromNativeConverter getFromNativeConverter(SignatureType type, FromNativeContext context) {
            Integer count = fromConverterStats.get(type.getDeclaredType());
            fromConverterStats.put(type.getDeclaredType(), count != null ? count + 1 : 1);
            return typeMapper.getFromNativeConverter(type, context);
        }

        @Override
        public ToNativeConverter getToNativeConverter(SignatureType type, ToNativeContext context) {
            Integer count = toConverterStats.get(type.getDeclaredType());
            toConverterStats.put(type.getDeclaredType(), count != null ? count + 1 : 1);
            return typeMapper.getToNativeConverter(type, context);
        }

        public int getFromNativeCount(Class type) {
            Integer count = fromConverterStats.get(type);
            return count != null ? count : 0;
        }

        public int getToNativeCount(Class type) {
            Integer count = toConverterStats.get(type);
            return count != null ? count : 0;
        }
    }

    private SignatureTypeMapper defaultTypeMapper;
    @Before
    public void setUp() {
        defaultTypeMapper = new CachingTypeMapper(new TestTypeMapper());
    }

    private ToNativeConverter getToNativeConverter(SignatureTypeMapper typeMapper, Method m, int parameterIndex) {
        ToNativeContext toNativeContext = new MethodParameterContext(m, parameterIndex);
        SignatureType signatureType = DefaultSignatureType.create(m.getParameterTypes()[parameterIndex], toNativeContext);
        return typeMapper.getToNativeConverter(signatureType, toNativeContext);
    }

    private FromNativeConverter getFromNativeConverter(SignatureTypeMapper typeMapper, Method m) {
        FromNativeContext fromNativeContext = new MethodResultContext(m);
        SignatureType signatureType = DefaultSignatureType.create(m.getReturnType(), fromNativeContext);
        return typeMapper.getFromNativeConverter(signatureType, fromNativeContext);
    }


    @Test public void intReturnHasNoConverter() {
        assertNull(getFromNativeConverter(defaultTypeMapper, getLibMethod("ret_int")));
    }

    @Test public void sameResultTypeHasSameConverter() {
        FromNativeConverter converter;
        assertNotNull(converter = getFromNativeConverter(defaultTypeMapper, getLibMethod("ret_enumset1a")));
        assertSame(converter, getFromNativeConverter(defaultTypeMapper, getLibMethod("ret_enumset1b")));
    }

    @Test public void differentEnumSet() {
        FromNativeConverter converter1;
        assertNotNull(converter1 = getFromNativeConverter(defaultTypeMapper, getLibMethod("ret_enumset1a")));
        assertSame(converter1,  getFromNativeConverter(defaultTypeMapper, getLibMethod("ret_enumset1b")));
        FromNativeConverter converter2;
        assertNotNull(converter2 = getFromNativeConverter(defaultTypeMapper, getLibMethod("ret_enumset2a")));
        assertSame(converter2, getFromNativeConverter(defaultTypeMapper, getLibMethod("ret_enumset2b")));
        assertNotSame(converter1, converter2);
    }


    @Test public void integerSet() {
        assertNull(getFromNativeConverter(defaultTypeMapper, getLibMethod("ret_intset")));
    }


    @Test public void differentAnnotations() {
        ToNativeConverter converter1, converter2;
        Method m = getLibMethod("annotated_params", long[].class, long[].class);
        assertNotNull(converter1 = getToNativeConverter(defaultTypeMapper, m, 0));
        assertNotNull(converter2 = getToNativeConverter(defaultTypeMapper, m, 1));
        assertNotSame(converter1, converter2);
    }

    @Test public void uncacheableConverter() {
        CountingTypeMapper counter;
        SignatureTypeMapper typeMapper = new CachingTypeMapper(counter = new CountingTypeMapper(new TestTypeMapper()));
        ToNativeConverter converter1, converter2;
        Method m = getLibMethod("int_array_params", int[].class, int[].class);
        assertNotNull(converter1 = getToNativeConverter(typeMapper, m, 0));
        assertEquals(1, counter.getToNativeCount(int[].class));
        assertNotNull(converter2 = getToNativeConverter(typeMapper, m, 1));
        assertEquals(2, counter.getToNativeCount(int[].class));
        assertNotSame(converter1, converter2);
    }

    @Test public void converterIsCached() {
        CountingTypeMapper counter;
        SignatureTypeMapper typeMapper = new CachingTypeMapper(counter = new CountingTypeMapper(new TestTypeMapper()));
        FromNativeConverter converter1;
        assertNotNull(converter1 = getFromNativeConverter(typeMapper, getLibMethod("ret_enumset1a")));
        assertEquals(1, counter.getFromNativeCount(Set.class));
        assertSame(converter1,  getFromNativeConverter(typeMapper, getLibMethod("ret_enumset1b")));
        assertEquals(1, counter.getFromNativeCount(Set.class));
        FromNativeConverter converter2;
        assertNotNull(converter2 = getFromNativeConverter(typeMapper, getLibMethod("ret_enumset2a")));
        assertEquals(2, counter.getFromNativeCount(Set.class));
        assertSame(converter2,  getFromNativeConverter(typeMapper, getLibMethod("ret_enumset2b")));
        assertNotSame(converter1, converter2);
        assertEquals(2, counter.getFromNativeCount(Set.class));
    }

}
