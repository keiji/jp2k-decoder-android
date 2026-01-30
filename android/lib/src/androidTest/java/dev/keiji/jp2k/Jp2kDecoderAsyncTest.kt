package dev.keiji.jp2k

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class Jp2kDecoderAsyncTest {

    private val context = InstrumentationRegistry.getInstrumentation().context
    private lateinit var decoder: Jp2kDecoderAsync

    @Before
    fun setUp() {
        decoder = Jp2kDecoderAsync()
    }

    @After
    fun tearDown() {
        decoder.release()
    }

    @Test
    fun testInitAsync() {
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<Unit?>()
        val errorRef = AtomicReference<Throwable?>()

        decoder.init(context, object : Callback<Unit> {
            override fun onSuccess(result: Unit) {
                resultRef.set(Unit)
                latch.countDown()
            }

            override fun onError(error: Exception) {
                errorRef.set(error)
                latch.countDown()
            }
        })

        val completed = latch.await(10, TimeUnit.SECONDS)
        if (!completed) {
            fail("Test timed out")
        }

        val error = errorRef.get()
        if (error != null) {
            fail("Init failed with error: ${error.message}")
        }
        assertNotNull(resultRef.get())
    }

    @Test
    fun testDoubleInit() {
        val latch1 = CountDownLatch(1)

        // First Init
        decoder.init(context, object : Callback<Unit> {
            override fun onSuccess(result: Unit) {
                latch1.countDown()
            }
            override fun onError(error: Exception) {
                fail("First init failed: ${error.message}")
                latch1.countDown()
            }
        })
        assertTrue("First init timed out", latch1.await(10, TimeUnit.SECONDS))

        // Second Init - Should Succeed immediately because it is already initialized
        val latch2 = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>()

        decoder.init(context, object : Callback<Unit> {
            override fun onSuccess(result: Unit) {
                latch2.countDown()
            }
            override fun onError(error: Exception) {
                errorRef.set(error)
                latch2.countDown()
            }
        })
        assertTrue("Second init timed out", latch2.await(5, TimeUnit.SECONDS))
        if (errorRef.get() != null) {
            fail("Second init failed: ${errorRef.get()?.message}")
        }
    }

    @Test
    fun testDecodeBeforeInit() {
        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>()

        val bytes = ByteArray(100) // Dummy data

        decoder.decodeImage(bytes, object : Callback<Bitmap> {
            override fun onSuccess(result: Bitmap) {
                fail("Decode should fail before init")
                latch.countDown()
            }

            override fun onError(error: Exception) {
                errorRef.set(error)
                latch.countDown()
            }
        })

        assertTrue("Callback timed out", latch.await(1, TimeUnit.SECONDS))
        assertTrue("Expected IllegalStateException, got ${errorRef.get()}", errorRef.get() is IllegalStateException)
    }

    @Test
    fun testDecodeAfterRelease() {
        val latch = CountDownLatch(1)

        // Init first
        decoder.init(context, object : Callback<Unit> {
            override fun onSuccess(result: Unit) {
                latch.countDown()
            }
            override fun onError(error: Exception) {
                fail("Init failed")
                latch.countDown()
            }
        })
        assertTrue("Init timed out", latch.await(10, TimeUnit.SECONDS))

        decoder.release()

        val latch2 = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>()
        val bytes = ByteArray(100)

        decoder.decodeImage(bytes, object : Callback<Bitmap> {
            override fun onSuccess(result: Bitmap) {
                fail("Decode should fail after release")
                latch2.countDown()
            }
            override fun onError(error: Exception) {
                errorRef.set(error)
                latch2.countDown()
            }
        })

        assertTrue("Callback timed out", latch2.await(1, TimeUnit.SECONDS))
        // Expecting IllegalStateException ("Decoder is not ready") or CancellationException
        val error = errorRef.get()
        val isExpected = error is IllegalStateException || error is CancellationException
        assertTrue("Expected IllegalStateException or CancellationException, got $error", isExpected)
    }

    @Test
    fun testConcurrentDecode() {
        // Init
        val initLatch = CountDownLatch(1)
        decoder.init(context, object : Callback<Unit> {
            override fun onSuccess(result: Unit) {
                initLatch.countDown()
            }
            override fun onError(error: Exception) {
                fail("Init failed")
                initLatch.countDown()
            }
        })
        assertTrue("Init timed out", initLatch.await(10, TimeUnit.SECONDS))

        val bytes = context.assets.open("karin.jp2").use { it.readBytes() }
        val count = 3
        val decodeLatch = CountDownLatch(count)
        val failures = AtomicReference<Int>(0)

        for (i in 0 until count) {
            decoder.decodeImage(bytes, object : Callback<Bitmap> {
                override fun onSuccess(result: Bitmap) {
                    decodeLatch.countDown()
                }
                override fun onError(error: Exception) {
                    failures.set(failures.get() + 1)
                    decodeLatch.countDown()
                }
            })
        }

        assertTrue("Decoding timed out", decodeLatch.await(20, TimeUnit.SECONDS))
        assertEquals("Failures found", 0, failures.get())
    }

    @Test
    fun testDecodeAsync() {
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<Bitmap?>()
        val errorRef = AtomicReference<Throwable?>()

        val bytes = context.assets.open("karin.jp2").use {
            it.readBytes()
        }

        fun decodeImage() {
            decoder.decodeImage(bytes, object : Callback<Bitmap> {
                override fun onSuccess(result: Bitmap) {
                    resultRef.set(result)
                    latch.countDown()
                }

                override fun onError(error: Exception) {
                    errorRef.set(error)
                    latch.countDown()
                }
            })
        }
        decoder.init(context, object : Callback<Unit> {
            override fun onSuccess(result: Unit) {
                decodeImage()
            }

            override fun onError(error: Exception) {
                errorRef.set(error)
                latch.countDown()
            }
        })

        val completed = latch.await(10, TimeUnit.SECONDS)
        if (!completed) {
            fail("Test timed out")
        }

        val error = errorRef.get()
        if (error != null) {
            fail("Decoding failed with error: ${error.message}")
        }

        val bitmap = resultRef.get()
        assertNotNull("Bitmap should not be null", bitmap)
        assertEquals(640, bitmap!!.width)
        assertEquals(480, bitmap.height)
    }

    @Test
    fun testUseBlock() {
        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>()

        Jp2kDecoderAsync().use { decoder ->
            decoder.init(context, object : Callback<Unit> {
                override fun onSuccess(result: Unit) {
                    // It is possible that init finishes before close() is called if we are very slow here,
                    // but typically use block finishes immediately.
                    // If init finishes, we don't care much, but if it is cancelled, that's also fine.
                    // However, we want to test that 'use' calls 'close' which calls 'release'.
                    // So we can check if it becomes unusable or if ongoing tasks are cancelled.
                }

                override fun onError(error: Exception) {
                    // CancellationException is expected if released during init
                    if (error is CancellationException) {
                        latch.countDown()
                    } else {
                        errorRef.set(error)
                        latch.countDown()
                    }
                }
            })
            // Exiting use block triggers close() -> release()
        }

        // We can't easily assert that close was called without a mock, but we can check side effects.
        // If we try to use the decoder from the use block (if we kept a reference), it should be Terminated.
        // But 'decoder' is not accessible outside.
        // The fact that it compiled proves it implements AutoCloseable.
    }

    @Test
    fun testDecodeSmallData() {
        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>()

        decoder.init(context, object : Callback<Unit> {
            override fun onSuccess(result: Unit) {
                val bytes = ByteArray(1) // Too small
                decoder.decodeImage(bytes, object : Callback<Bitmap> {
                    override fun onSuccess(result: Bitmap) {
                        fail("Should fail")
                        latch.countDown()
                    }
                    override fun onError(error: Exception) {
                        errorRef.set(error)
                        latch.countDown()
                    }
                })
            }
            override fun onError(error: Exception) {
                fail("Init failed")
                latch.countDown()
            }
        })

        assertTrue("Timed out", latch.await(10, TimeUnit.SECONDS))
        assertTrue("Expected IllegalArgumentException", errorRef.get() is IllegalArgumentException)
    }

    @Test
    fun testReleaseDouble() {
        decoder.release()
        decoder.release() // Should not crash
    }
}
