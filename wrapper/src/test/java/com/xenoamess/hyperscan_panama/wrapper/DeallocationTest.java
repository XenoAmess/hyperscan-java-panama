package com.xenoamess.hyperscan_panama.wrapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeallocationTest {
    @Test
    public void ensureResourcesAreProperlyFreed() throws CompileErrorException, IOException {
        Database db = Database.compile(new Expression("Te?st"));

        try (Scanner scanner = new Scanner()) {
            scanner.allocScratch(db);

            List<Match> matches = scanner.scan(db, "Test");
            assertEquals(1, matches.size());

            Scanner additionalScanner = new Scanner();
            additionalScanner.allocScratch(db);
            additionalScanner.close();
        }

        long size = db.getSize();
        assertTrue(size > 0);

        db.close();

        try {
            db.getSize();
            fail("Should throw after close");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    void nativeHandlesShouldBeGarbageCollectable() throws CompileErrorException, IOException {
        Database db = Database.compile(new Expression("Te?st"));
        Scanner scanner = new Scanner();
        scanner.allocScratch(db);
        scanner.close();

        WeakReference<Database> dbRef = new WeakReference<>(db);
        WeakReference<Scanner> scannerRef = new WeakReference<>(scanner);
        db = null;
        scanner = null;

        assertNotNull(dbRef);
        assertNotNull(scannerRef);

        for (int i = 0; i < 100; i++) {
            System.gc();
        }

        assertNull(dbRef.get());
        assertNull(scannerRef.get());
    }

    @Test
    void databaseShouldThrowExceptionOnCallingSizeAfterClose() {
        try {
            Database db = Database.compile(new Expression("test"));
            db.close();
            db.getSize();
            fail("We should not be able to call getSize on a deallocated database");
        } catch (IllegalStateException e) {
            // expected
        } catch (Exception t) {
            fail(t);
        }
    }

    @Test
    void scrannerShouldThrowExceptionOnCallingSizeAfterClose() throws CompileErrorException, IOException {
        try {
            Database db = Database.compile(new Expression("test"));
            Scanner scanner = new Scanner();
            scanner.allocScratch(db);
            scanner.close();
            scanner.getSize();
            fail("We should not be able to call getSize on a deallocated scanner scratch space");
        } catch (IllegalStateException e) {
            // expected
        } catch (Exception t) {
            fail(t);
        }
    }
}
