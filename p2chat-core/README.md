# P2Chat-Core · Standalone BLE Proof-of-Concept

> Self-contained laptop-to-laptop messaging demo: **BLE GATT Broadcast → AES-256-GCM Encryption → Wireless BLE Scan & Read → Real-time Decryption**.

---

## 📁 How to Share with a Friend

Simply copy or zip the `p2chat-core` folder and give it to your friend (via USB drive, AirDrop, email, or GitHub)!

```
p2chat-core/
├── sender.py           # Broadcaster script (Interactive CLI + GATT Server)
├── receiver.py         # Client script (Discovers, decrypts & listens)
├── requirements.txt    # Bumble, Bleak, PyCryptodome
├── start_sender.bat    # Double-click to start Sender (Windows)
├── start_receiver.bat  # Double-click to start Receiver (Windows)
├── start_sender.sh     # Mac / Linux Sender launcher
└── start_receiver.sh   # Mac / Linux Receiver launcher
```

---

## 🚀 Laptop-to-Laptop Presentation Setup (2 Laptops)

Both laptops should be connected to the **same Wi-Fi network** or hotspot (or have Bluetooth turned on).

### Step 1: Laptop A (Sender)
Double-click `start_sender.bat` (or run `python sender.py`):
1. It automatically sets up the environment and starts the BLE GATT Server.
2. It displays Laptop A's **Local IP Address** on the screen (e.g. `192.168.1.15`).

### Step 2: Laptop B (Friend's Laptop - Receiver)
Double-click `start_receiver.bat` (or run `python receiver.py`):
1. Choose option `1` (Virtual BLE over Wi-Fi).
2. Enter Laptop A's IP address when prompted (or run `python receiver.py --ip 192.168.1.15`).

### Step 3: Start Live Chatting!
- On Laptop A: Type any message in the console (e.g. `"Hello from Laptop A!"`) and press **Enter**.
- On Laptop B: The receiver automatically decrypts the message and displays it in real-time!

---

## 📡 Hardware Bluetooth Mode (Physical BLE Dongles)

If both laptops have USB Bluetooth dongles / HCI adapters:
- **Laptop A**: Run `python sender.py --transport usb:0`
- **Laptop B**: Run `python receiver.py --ble`

---

## 🔐 Wire Format & UUIDs (Matches Android `BleConstants.kt`)

| Component | Specification |
|---|---|
| **Service UUID** | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` |
| **Characteristic UUID** | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` |
| **Encryption Algorithm** | AES-256-GCM (Authenticated Encryption) |
| **Payload Structure** | `nonce (12 bytes) + tag (16 bytes) + len (4 bytes) + ciphertext` |
