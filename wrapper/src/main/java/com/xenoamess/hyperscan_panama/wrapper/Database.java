package com.xenoamess.hyperscan_panama.wrapper;

import com.xenoamess.hyperscan_panama.jni.HyperscanJni;
import com.xenoamess.hyperscan_panama.jni.HyperscanNativeLoader;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.io.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;
import static java.util.function.Function.identity;

/**
 * Database containing compiled expressions ready for scanning using the Scanner
 */
public class Database implements Closeable {
    static {
        HyperscanNativeLoader.load();
    }

    private static final HyperscanJni JNI = HyperscanNativeLoader.loadJni();

    private static final ThreadLocal<MemorySegment> SIZE_BUFFER = ThreadLocal.withInitial(
            () -> Arena.global().allocate(JNI.size_t())
    );

    private static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create();

    private final List<Expression> expressionsSource;
    private final boolean hasIds;
    private final int expressionCount;
    private final Expression[] expressionsById;
    private final IntExpressionMap sparseExpressions;
    private Map<Integer, Expression> expressions; // lazy, built on first access

    private final State state;
    private final java.lang.ref.Cleaner.Cleanable cleanable;

    private static class State implements Runnable {
        private volatile MemorySegment database;

        State(MemorySegment database) {
            this.database = database;
        }

        MemorySegment getDatabase() {
            MemorySegment database = this.database;
            if (database == null) {
                throw new IllegalStateException("Database has already been deallocated");
            }
            return database;
        }

        @Override
        public synchronized void run() {
            MemorySegment database = this.database;
            if (database != null) {
                this.database = null;
                JNI.hsFreeDatabase(database);
            }
        }
    }

    static final class IntExpressionMap {
        private final int[] keys;
        private final Expression[] values;
        private final boolean[] used;
        private final int mask;

        IntExpressionMap(int initialSize) {
            int capacity = 1;
            while (capacity < initialSize * 2) {
                capacity <<= 1;
            }
            this.keys = new int[capacity];
            this.values = new Expression[capacity];
            this.used = new boolean[capacity];
            this.mask = capacity - 1;
        }

        void put(int key, Expression value) {
            int idx = hash(key) & mask;
            while (used[idx]) {
                idx = (idx + 1) & mask;
            }
            used[idx] = true;
            keys[idx] = key;
            values[idx] = value;
        }

        Expression get(int key) {
            int idx = hash(key) & mask;
            while (used[idx]) {
                if (keys[idx] == key) {
                    return values[idx];
                }
                idx = (idx + 1) & mask;
            }
            return null;
        }

        private static int hash(int key) {
            return key ^ (key >>> 16);
        }
    }

    private Database(MemorySegment database, List<Expression> expressions) {
        this.state = new State(database);
        this.cleanable = CLEANER.register(this, state);
        this.expressionCount = expressions.size();
        this.expressionsSource = List.copyOf(expressions);

        this.hasIds = expressions.get(0).getId() != null;

        int maxId = -1;
        if (hasIds) {
            for (Expression expression : expressions) {
                Integer id = expression.getId();
                if (id != null && id > maxId) {
                    maxId = id;
                }
            }
        } else {
            maxId = expressionCount - 1;
        }
        if (maxId >= 0 && maxId <= expressionCount * 2) {
            this.expressionsById = new Expression[maxId + 1];
            this.sparseExpressions = null;
        } else {
            this.expressionsById = new Expression[0];
            this.sparseExpressions = new IntExpressionMap(expressionCount);
        }

        if (hasIds) {
            for (Expression expression : expressions) {
                Integer id = expression.getId();
                if (id != null && id < expressionsById.length) {
                    if (expressionsById[id] != null) {
                        throw new IllegalStateException("Expression ID must be unique within a Database.");
                    }
                    expressionsById[id] = expression;
                }
                if (sparseExpressions != null && id != null) {
                    if (sparseExpressions.get(id) != null) {
                        throw new IllegalStateException("Expression ID must be unique within a Database.");
                    }
                    sparseExpressions.put(id, expression);
                }
            }
        } else {
            int i = 0;
            for (Expression expression : expressions) {
                expressionsById[i] = expression;
                i++;
            }
        }
    }

    private static String readErrorMessage(MemorySegment errorPtr) {
        if (errorPtr.address() == 0) {
            return null;
        }
        MemorySegment readableError = errorPtr.reinterpret(JNI.hsCompileErrorLayout().byteSize());
        MemorySegment messagePtr = JNI.hsCompileErrorMessage(readableError);
        if (messagePtr == null || messagePtr.address() == 0) {
            return null;
        }
        MemorySegment readable = messagePtr.reinterpret(1024, Arena.global(), null);
        return readable.getString(0);
    }

    private static void handleErrors(int hsError, MemorySegment error, List<Expression> expressions) throws CompileErrorException {
        if (hsError == 0) {
            return;
        }

        MemorySegment readableError = error.reinterpret(JNI.hsCompileErrorLayout().byteSize());
        String message = readErrorMessage(error);
        if (hsError == JNI.hsCompilerError()) {
            int expressionIndex = JNI.hsCompileErrorExpression(readableError);
            Expression expression = expressionIndex < 0 ? null : expressions.get(expressionIndex);
            JNI.hsFreeCompileError(error);
            throw new CompileErrorException(message, expression);
        } else {
            JNI.hsFreeCompileError(error);
            throw HyperscanException.hsErrorToException(hsError);
        }
    }

    /**
     * compile an expression into a database to use for scanning
     *
     * @param expression Expression to compile
     * @return Compiled database
     * @throws CompileErrorException If the expression cannot be compiled
     */
    public static Database compile(Expression expression) throws CompileErrorException {
        return compile(singletonList(expression));
    }

    /**
     * Compiles a list of expressions into a database to use for scanning
     *
     * @param expressions List of expressions to compile
     * @return Compiled database
     * @throws CompileErrorException If any of the expressions cannot be compiled
     */
    public static Database compile(Expression... expressions) throws CompileErrorException {
        return Database.compile(Arrays.asList(expressions));
    }

    /**
     * Compiles a list of expressions into a database to use for scanning
     *
     * @param expressions List of expressions to compile
     * @return Compiled database
     * @throws CompileErrorException If any of the expressions cannot be compiled
     */
    public static Database compile(List<Expression> expressions) throws CompileErrorException {
        if (expressions == null) {
            throw new NullPointerException("expressions must not be null");
        }
        if (expressions.isEmpty()) {
            throw new CompileErrorException("No expressions provided", null);
        }

        try (
                NativeExpressionCollection nativeExpressions = new NativeExpressionCollection(expressions);
                Arena arena = Arena.ofConfined()
        ) {
            MemorySegment error = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment db = arena.allocate(ValueLayout.ADDRESS);

            int hsError = JNI.hsCompileMulti(
                    nativeExpressions.getExpressionsBytes(),
                    nativeExpressions.getNativeFlags(),
                    nativeExpressions.getNativeIds(),
                    nativeExpressions.getSize(),
                    JNI.hsModeBlock(),
                    MemorySegment.NULL,
                    db,
                    error);

            MemorySegment errorPtr = error.get(ValueLayout.ADDRESS, 0);
            if (hsError != 0) {
                handleErrors(hsError, errorPtr, expressions);
            }

            MemorySegment database = db.get(ValueLayout.ADDRESS, 0);
            database = database.reinterpret(Long.MAX_VALUE);
            return new Database(database, expressions);
        }
    }

    MemorySegment getDatabase() {
        return state.getDatabase();
    }

    /**
     * Get the database size in bytes
     *
     * @return count of bytes
     */
    public long getSize() {
        MemorySegment database = state.getDatabase();
        MemorySegment size = SIZE_BUFFER.get();
        int hsError = JNI.hsDatabaseSize(database, size);
        if (hsError != 0) {
            throw HyperscanException.hsErrorToException(hsError);
        }
        return JNI.readSize_t(size, 0);
    }

    Expression getExpression(int id) {
        if (id >= 0 && id < expressionsById.length) {
            Expression expression = expressionsById[id];
            if (expression != null) {
                return expression;
            }
        }
        return sparseExpressions != null ? sparseExpressions.get(id) : getExpressionsMap().get(id);
    }

    final Expression[] getExpressionsById() {
        return expressionsById;
    }

    final IntExpressionMap getSparseExpressions() {
        return sparseExpressions;
    }

    private Map<Integer, Expression> getExpressionsMap() {
        Map<Integer, Expression> map = expressions;
        if (map == null) {
            synchronized (this) {
                map = expressions;
                if (map == null) {
                    map = new HashMap<>(expressionCount);
                    if (hasIds) {
                        for (Expression expression : expressionsSource) {
                            map.put(expression.getId(), expression);
                        }
                    } else {
                        int i = 0;
                        for (Expression expression : expressionsSource) {
                            map.put(i, expression);
                            i++;
                        }
                    }
                    expressions = map;
                }
            }
        }
        return map;
    }

    @Override
    public void close() {
        cleanable.clean();
    }

    /**
     * Saves the expressions and the compiled database to an OutputStream.
     * Expression contexts are not saved.
     * The OutputStream is not closed.
     *
     * @param out stream to write to
     * @throws IOException If an I/O error occurs while writing to the stream
     */
    public void save(OutputStream out) throws IOException {
        save(out, out);
    }

    /**
     * Saves the expressions and the compiled database to (possibly) distinct OutputStreams.
     * All of the expressions are saved to expressionsOut before any of the database is saved to databaseOut so it's safe
     * to use the same backing OutputStream for both parameters.
     * Expression contexts are not saved.
     * Neither of the OutputStream is closed.
     *
     * @param expressionsOut stream to write expressions to
     * @param databaseOut    stream to write database to
     * @throws IOException If an I/O error occurs while writing to either stream
     */
    public void save(OutputStream expressionsOut, OutputStream databaseOut) throws IOException {
        DataOutputStream expressionsDataOut = new DataOutputStream(expressionsOut);
        // How many expressions will be present. We need this to know when to stop reading.
        expressionsDataOut.writeInt(expressionCount);
        for (Expression expression : getExpressionsMap().values()) {
            if (expression == null) {
                continue;
            }

            // Expression id
            expressionsDataOut.writeInt(expression.getId() == null ? -1 : expression.getId());
            // Expression pattern
            expressionsDataOut.writeUTF(expression.getExpression());
            // Flag count
            EnumSet<ExpressionFlag> flags = expression.getFlags();
            expressionsDataOut.writeInt(flags.size());
            for (ExpressionFlag flag : flags) {
                // Bitmask for each flag
                expressionsDataOut.writeInt(flag.getBits());
            }
        }
        expressionsDataOut.flush();

        // Serialize the database into a contiguous native memory block. The native
        // library allocates the buffer and returns both the pointer and the length
        // via output parameters (char **bytes, size_t *length).
        MemorySegment database = state.getDatabase();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytesOut = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment size = arena.allocate(JNI.size_t());
            int hsError = JNI.hsSerializeDatabase(database, bytesOut, size);
            if (hsError != 0) {
                throw HyperscanException.hsErrorToException(hsError);
            }

            long length = JNI.readSize_t(size, 0);
            MemorySegment bytes = bytesOut.get(ValueLayout.ADDRESS, 0).reinterpret(length);

            DataOutputStream databaseDataOut = new DataOutputStream(new BufferedOutputStream(databaseOut));
            databaseDataOut.writeInt((int) length);

            byte[] buffer = new byte[65536];
            long offset = 0;
            long remaining = length;
            while (remaining > 0) {
                int chunk = (int) Math.min(remaining, buffer.length);
                MemorySegment.copy(bytes, ValueLayout.JAVA_BYTE, offset, buffer, 0, chunk);
                databaseDataOut.write(buffer, 0, chunk);
                offset += chunk;
                remaining -= chunk;
            }
            databaseDataOut.flush();

            // The library allocated the serialized buffer with its default allocator;
            // free it with the standard C free().
            JNI.free(bytes);
        }
    }

    /**
     * Loads the database saved via {@link #save(OutputStream)}.
     * The saved payload contains platform-specific formatting so it should be loaded on a compatible platform.
     * All Expression contexts will be null.
     *
     * @param in stream to read from
     * @return loaded Database
     * @throws IOException If an I/O error occurs while reading from the stream
     */
    public static Database load(InputStream in) throws IOException {
        return load(in, in);
    }

    /**
     * Loads the database saved via {@link #save(OutputStream, OutputStream)}.
     * The saved payload contains platform-specific formatting so it should be loaded on a compatible platform.
     *
     * @param expressionsIn stream to read expressions from
     * @param databaseIn    stream to read database from
     * @return loaded Database
     * @throws IOException If an I/O error occurs while reading from either stream
     */
    public static Database load(InputStream expressionsIn, InputStream databaseIn) throws IOException {
        // DataInputStream doesn't buffer so it will only read as much as we ask for.
        // This makes it safe to use even if expressionsIn and databaseIn are the same streams.
        DataInputStream expressionsDataIn = new DataInputStream(expressionsIn);
        int expressionCount = expressionsDataIn.readInt();
        List<Expression> expressions = new ArrayList<>(expressionCount);

        // Setup a lookup map for expression flags
        Map<Integer, ExpressionFlag> bitmaskToFlag = Arrays.stream(ExpressionFlag.values())
                .collect(Collectors.toMap(ExpressionFlag::getBits, identity()));

        for (int i = 0; i < expressionCount; i++) {
            int id = expressionsDataIn.readInt();
            String pattern = expressionsDataIn.readUTF();
            int flagCount = expressionsDataIn.readInt();
            EnumSet<ExpressionFlag> flags = EnumSet.noneOf(ExpressionFlag.class);
            for (int j = 0; j < flagCount; j++) {
                int bitmask = expressionsDataIn.readInt();
                flags.add(bitmaskToFlag.get(bitmask));
            }
            expressions.add(new Expression(pattern, flags, id == -1 ? null : id));
        }

        DataInputStream databaseDataIn = new DataInputStream(databaseIn);
        int length = databaseDataIn.readInt();
        byte[] bytes = new byte[length];
        databaseDataIn.readFully(bytes);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytePtr = arena.allocate(length);
            bytePtr.copyFrom(MemorySegment.ofArray(bytes));

            MemorySegment db = arena.allocate(ValueLayout.ADDRESS);
            int hsError = JNI.hsDeserializeDatabase(bytePtr, (long) length, db);
            if (hsError != 0) {
                throw HyperscanException.hsErrorToException(hsError);
            }
            MemorySegment database = db.get(ValueLayout.ADDRESS, 0);
            database = database.reinterpret(Long.MAX_VALUE);
            return new Database(database, expressions);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Database database = (Database) o;
        return expressionCount == database.expressionCount && getExpressionsMap().equals(database.getExpressionsMap());
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(expressionCount);
        result = 31 * result + getExpressionsMap().hashCode();
        return result;
    }
}
