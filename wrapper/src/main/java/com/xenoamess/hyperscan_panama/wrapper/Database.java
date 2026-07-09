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

    private static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create();

    private final Map<Integer, Expression> expressions;
    private final int expressionCount;

    private final State state;
    private final java.lang.ref.Cleaner.Cleanable cleanable;

    private static class State implements Runnable {
        private MemorySegment database;

        State(MemorySegment database) {
            this.database = database;
        }

        synchronized MemorySegment getDatabase() {
            if (database == null) {
                throw new IllegalStateException("Database has already been deallocated");
            }
            return database;
        }

        @Override
        public synchronized void run() {
            if (database != null) {
                JNI.hsFreeDatabase(database);
                database = null;
            }
        }
    }

    private Database(MemorySegment database, List<Expression> expressions) {
        this.state = new State(database);
        this.cleanable = CLEANER.register(this, state);
        this.expressionCount = expressions.size();

        boolean hasIds = expressions.get(0).getId() != null;

        this.expressions = new HashMap<>(expressionCount);
        if (hasIds) {
            for (Expression expression : expressions) {
                if (this.expressions.put(expression.getId(), expression) != null)
                    throw new IllegalStateException("Expression ID must be unique within a Database.");
            }
        } else {
            int i = 0;
            for (Expression expression : expressions) {
                this.expressions.put(i++, expression);
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
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment readable = messagePtr.reinterpret(4096, arena, null);
            return readable.getString(0);
        }
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

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment size = arena.allocate(JNI.size_t());
            int hsError = JNI.hsDatabaseSize(database, size);
            if (hsError != 0) {
                throw HyperscanException.hsErrorToException(hsError);
            }
            return JNI.readSize_t(size, 0);
        }
    }

    Expression getExpression(int id) {
        return expressions.get(id);
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
        for (Expression expression : expressions.values()) {
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

            DataOutputStream databaseDataOut = new DataOutputStream(databaseOut);
            databaseDataOut.writeInt((int) length);
            databaseDataOut.write(bytes.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE));
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
        return expressionCount == database.expressionCount && expressions.equals(database.expressions);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(expressionCount);
        result = 31 * result + expressions.hashCode();
        return result;
    }
}
