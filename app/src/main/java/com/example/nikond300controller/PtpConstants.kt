package com.example.nikond300controller

object PtpConstants {
    // PTP Packet Types
    const val PACKET_TYPE_COMMAND = 1
    const val PACKET_TYPE_DATA = 2
    const val PACKET_TYPE_RESPONSE = 3
    const val PACKET_TYPE_EVENT = 4

    // PTP Operation Codes
    const val OP_GET_DEVICE_INFO = 0x1001
    const val OP_OPEN_SESSION = 0x1002
    const val OP_CLOSE_SESSION = 0x1003
    const val OP_GET_STORAGE_IDS = 0x1004
    const val OP_GET_STORAGE_INFO = 0x1005
    const val OP_GET_OBJECT_HANDLES = 0x1007
    const val OP_GET_OBJECT_INFO = 0x1008
    const val OP_GET_OBJECT = 0x1009
    const val OP_GET_THUMB = 0x100A
    const val OP_GET_DEVICE_PROP_DESC = 0x1014
    const val OP_GET_DEVICE_PROP_VALUE = 0x1015
    const val OP_SET_DEVICE_PROP_VALUE = 0x1016
    const val OP_INITIATE_CAPTURE = 0x100E

    // PTP Response Codes
    const val RESP_OK = 0x2001
    const val RESP_DEVICE_BUSY = 0x2002
    const val RESP_SESSION_NOT_OPEN = 0x2003
    const val RESP_INVALID_TRANSACTION_ID = 0x2004
    const val RESP_OPERATION_NOT_SUPPORTED = 0x2005
    const val RESP_PARAMETER_NOT_SUPPORTED = 0x2006
    const val RESP_PARAMETER_READ_ONLY = 0x201C
    const val RESP_SESSION_ALREADY_OPEN = 0x201E
    const val RESP_NIKON_HARDWARE_ERROR = 0x2019 // Focus Not Locked

    // Device Property Codes
    const val PROP_BATTERY_LEVEL = 0x5001
    const val PROP_WHITE_BALANCE = 0x5005
    const val PROP_NIKON_ISO_AUTO = 0xD054
    const val PROP_NIKON_ISO_AUTO_MAX_ISO = 0xD183
    const val PROP_NIKON_ISO_AUTO_MIN_SHUTTER = 0xD164
    const val PROP_NIKON_BATTERY_LEVEL = 0xD1B3
    const val PROP_NIKON_WB_COLOR_TEMP = 0xD01E
    const val PROP_NIKON_WB_PRESET_NO = 0xD01F
    const val PROP_F_NUMBER = 0x5007
    const val PROP_FOCAL_LENGTH = 0x5008
    const val PROP_EXPOSURE_TIME = 0x500D
    const val PROP_EXPOSURE_PROGRAM_MODE = 0x500E
    const val PROP_EXPOSURE_INDEX = 0x500F // ISO
    const val PROP_EXPOSURE_BIAS_COMPENSATION = 0x5010
    const val PROP_FOCUS_MODE = 0x500A
    const val PROP_NIKON_LIVE_VIEW = 0x5013
    const val PROP_NIKON_SHUTTER_SPEED = 0xD100

    // NIKON specific codes
    const val OP_NIKON_INITIATE_CAPTURE = 0x90C0
    const val OP_NIKON_AF_DRIVE = 0x90C1
    const val OP_NIKON_START_LIVE_VIEW = 0x9201
    const val OP_NIKON_END_LIVE_VIEW = 0x9202
    const val OP_NIKON_GET_LIVE_VIEW_IMAGE = 0x9203
    const val OP_NIKON_GET_EVENT = 0x90C7
    const val OP_NIKON_DEVICE_READY = 0x90C8
    const val OP_NIKON_AF_AND_CAPTURE = 0x9207
}