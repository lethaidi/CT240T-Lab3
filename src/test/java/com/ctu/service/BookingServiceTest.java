package com.ctu.service;

import com.ctu.exception.PaymentFailedException;
import com.ctu.exception.RoomNotAvailableException;
import com.ctu.model.BookingReceipt;
import com.ctu.model.Room;
import com.ctu.repository.RoomRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Bài 1 - Kiểm thử dịch vụ đặt phòng khách sạn")
class BookingServiceTest {
    private static final String ROOM_ID = "R001";
    private static final String CARD = "4111111111111111";
    @Mock private RoomRepository roomRepository;
    @Mock private PaymentGateway paymentGateway;
    @InjectMocks private BookingService bookingService;
    private Room room;

    @BeforeEach
    void setUp() {
        room = new Room(ROOM_ID, 500_000, true);
    }

    @Test
    @DisplayName("Đặt phòng thành công: tính tiền, thu tiền rồi lưu phòng đã đặt")
    void testBookRoom_Success() {
        // Arrange
        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(paymentGateway.charge(CARD, 1_500_000)).thenReturn(true);
        // Act
        BookingReceipt receipt = bookingService.bookRoom(ROOM_ID, 3, CARD);
        // Assert
        assertAll(
            () -> assertFalse(room.isAvailable()),
            () -> assertEquals(ROOM_ID, room.getRoomId()),
            () -> assertEquals(ROOM_ID, receipt.getRoomId()),
            () -> assertEquals(1_500_000, receipt.getTotalAmount(), 0.001),
            () -> assertDoesNotThrow(() -> UUID.fromString(receipt.getBookingId()))
        );
        var order = inOrder(roomRepository, paymentGateway);
        order.verify(roomRepository, times(1)).findById(ROOM_ID);
        order.verify(paymentGateway, times(1)).charge(CARD, 1_500_000);
        order.verify(roomRepository, times(1)).save(room);
        verifyNoMoreInteractions(roomRepository, paymentGateway);
    }

    @Test
    @DisplayName("Không tìm thấy phòng: không thanh toán và không lưu")
    void testBookRoom_RoomNotFound_ThrowsException() {
        // Arrange
        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.empty());
        // Act
        var error = assertThrows(RoomNotAvailableException.class,
                () -> bookingService.bookRoom(ROOM_ID, 1, CARD));
        // Assert
        assertEquals("Khong tim thay phong: " + ROOM_ID, error.getMessage());
        verify(roomRepository, times(1)).findById(ROOM_ID);
        verify(roomRepository, never()).save(any());
        verifyNoInteractions(paymentGateway);
        verifyNoMoreInteractions(roomRepository);
    }

    @Test
    @DisplayName("Phòng đã đặt: không thanh toán và không lưu")
    void testBookRoom_RoomAlreadyBooked_ThrowsException() {
        // Arrange
        room.setAvailable(false);
        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        // Act
        var error = assertThrows(RoomNotAvailableException.class,
                () -> bookingService.bookRoom(ROOM_ID, 1, CARD));
        // Assert
        assertEquals("Phong da duoc dat truoc do: " + ROOM_ID, error.getMessage());
        assertFalse(room.isAvailable());
        verify(roomRepository, times(1)).findById(ROOM_ID);
        verify(roomRepository, never()).save(any());
        verifyNoInteractions(paymentGateway);
        verifyNoMoreInteractions(roomRepository);
    }

    @Test
    @DisplayName("Thanh toán thất bại: phòng vẫn trống và không lưu")
    void testBookRoom_PaymentFailed_ThrowsException() {
        // Arrange
        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(paymentGateway.charge(CARD, 1_000_000)).thenReturn(false);
        // Act
        var error = assertThrows(PaymentFailedException.class,
                () -> bookingService.bookRoom(ROOM_ID, 2, CARD));
        // Assert
        assertEquals("Thanh toan qua the credit card that bai!", error.getMessage());
        assertTrue(room.isAvailable());
        verify(roomRepository, times(1)).findById(ROOM_ID);
        verify(paymentGateway, times(1)).charge(CARD, 1_000_000);
        verify(roomRepository, never()).save(any());
        verifyNoMoreInteractions(roomRepository, paymentGateway);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    @DisplayName("Số đêm không dương bị từ chối trước khi truy cập phụ thuộc")
    void testBookRoom_InvalidNights_ThrowsException(int nights) {
        // Arrange: đầu vào hợp lệ khác được dùng chung.
        // Act
        var error = assertThrows(IllegalArgumentException.class,
                () -> bookingService.bookRoom(ROOM_ID, nights, CARD));
        // Assert
        assertEquals("Tham so dat phong khong hop le!", error.getMessage());
        verifyNoInteractions(roomRepository, paymentGateway);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t\n"})
    @DisplayName("Thẻ null, rỗng hoặc chỉ chứa khoảng trắng bị từ chối")
    void testBookRoom_NullOrEmptyCreditCard_ThrowsException(String card) {
        // Arrange: số đêm và mã phòng hợp lệ.
        // Act
        var error = assertThrows(IllegalArgumentException.class,
                () -> bookingService.bookRoom(ROOM_ID, 1, card));
        // Assert
        assertEquals("Tham so dat phong khong hop le!", error.getMessage());
        verifyNoInteractions(roomRepository, paymentGateway);
    }

    @Test
    @DisplayName("Mã phòng null bị từ chối trước khi tra cứu")
    void testBookRoom_NullRoomId_ThrowsException() {
        // Arrange: số đêm và thẻ hợp lệ.
        // Act
        var error = assertThrows(IllegalArgumentException.class,
                () -> bookingService.bookRoom(null, 1, CARD));
        // Assert
        assertEquals("Tham so dat phong khong hop le!", error.getMessage());
        verifyNoInteractions(roomRepository, paymentGateway);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, Integer.MAX_VALUE})
    @DisplayName("Số đêm hợp lệ: kiểm tra biên 1 và giá trị int lớn nhất")
    void testBookRoom_ValidNights_CalculatesTotal(int nights) {
        // Arrange
        double expected = 500_000.0 * nights;
        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(paymentGateway.charge(CARD, expected)).thenReturn(true);
        // Act
        BookingReceipt receipt = bookingService.bookRoom(ROOM_ID, nights, CARD);
        // Assert
        assertEquals(expected, receipt.getTotalAmount(), 0.001);
        assertFalse(room.isAvailable());
        verify(roomRepository, times(1)).findById(ROOM_ID);
        verify(paymentGateway, times(1)).charge(CARD, expected);
        verify(roomRepository, times(1)).save(room);
        verifyNoMoreInteractions(roomRepository, paymentGateway);
    }
}