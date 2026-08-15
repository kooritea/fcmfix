package sumicya.fcmself.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FcmReceiverDetectorTest {
    @Test
    public void detectsStandardFirebaseReceiver() {
        assertTrue(FcmReceiverDetector.isFcmReceiver(
                "com.google.firebase.iid.FirebaseInstanceIdReceiver"));
    }

    @Test
    public void detectsAppSpecificFirebaseReceivers() {
        assertTrue(FcmReceiverDetector.isFcmReceiver(
                "example.push.FirebaseInstanceIdReceiver"));
        assertTrue(FcmReceiverDetector.isFcmReceiver(
                "example.push.CustomFirebaseMessagingReceiver"));
    }

    @Test
    public void detectsLegacyGcmReceivers() {
        assertTrue(FcmReceiverDetector.isFcmReceiver(
                "com.google.android.gms.gcm.GcmReceiver"));
        assertTrue(FcmReceiverDetector.isFcmReceiver(
                "example.push.GcmReceiver"));
    }

    @Test
    public void detectsFirebaseMessagingServices() {
        assertTrue(FcmReceiverDetector.isFcmComponent(
                "com.example.MyFirebaseMessagingService"));
        assertTrue(FcmReceiverDetector.isFcmComponent(
                "com.google.firebase.messaging.FirebaseMessaging"));
        assertTrue(FcmReceiverDetector.isFcmComponent(
                "com.google.firebase.messaging.FirebaseMessagingService"));
    }

    @Test
    public void detectsGcmListenerServicesAndGenericFcmReceivers() {
        assertTrue(FcmReceiverDetector.isFcmComponent(
                "com.example.gcm.GcmListenerService"));
        assertTrue(FcmReceiverDetector.isFcmComponent(
                "com.example.push.FCMPushReceiver"));
        assertTrue(FcmReceiverDetector.isFcmComponent(
                "com.example.push.FcmReceiver"));
    }

    @Test
    public void doesNotTreatFirebaseAnalyticsAsMessaging() {
        assertFalse(FcmReceiverDetector.isFcmReceiver(
                "com.google.android.gms.measurement.AppMeasurementReceiver"));
    }

    @Test
    public void handlesMissingAndUnrelatedNames() {
        assertFalse(FcmReceiverDetector.isFcmReceiver(null));
        assertFalse(FcmReceiverDetector.isFcmReceiver("example.push.BootReceiver"));
    }
}
