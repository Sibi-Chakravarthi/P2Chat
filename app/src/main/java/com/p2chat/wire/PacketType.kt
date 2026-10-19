package com.p2chat.wire

enum class PacketType(val code: Byte) {
    CHAT(BleConstants.PACKET_TYPE_CHAT),
    KEY_ROTATE(BleConstants.PACKET_TYPE_KEY_ROTATE),
    ROSTER_SYNC(BleConstants.PACKET_TYPE_ROSTER_SYNC),
    PAIRWISE_INVITE(BleConstants.PACKET_TYPE_PAIRWISE_INVITE),
    BENCH_DATA(BleConstants.PACKET_TYPE_BENCH_DATA),
    BENCH_ACK(BleConstants.PACKET_TYPE_BENCH_ACK);

    companion object {
        fun fromCode(code: Byte): PacketType? = entries.firstOrNull { it.code == code }
    }
}
