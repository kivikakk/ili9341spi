from amaranth.lib import enum

__all__ = ["LcdCommand", "LCD_INIT_SEQUENCE", "LCD_INIT_ROM"]

class LcdCommand(enum.Enum, shape=8):
    NOP = 0x00
    SOFTWARE_RESET = 0x01
    READ_DISPLAY_ID = 0x04
    READ_DISPLAY_STATUS = 0x09
    SLEEP_IN = 0x10
    SLEEP_OUT = 0x11
    GAMMA_SET = 0x26
    DISPLAY_OFF = 0x28
    DISPLAY_ON = 0x29
    CASET = 0x2A
    PASET = 0x2B
    MEMORY_WRITE = 0x2C
    MEMORY_ACCESS_CTRL = 0x36
    COLMOD = 0x3A
    WRITE_MEMORY_CONTINUE = 0x3C
    FRAME_RATE_CTRL = 0xB1
    DISPLAY_FN_CTRL = 0xB6
    POWER_CTRL_1 = 0xC0
    POWER_CTRL_2 = 0xC1
    VCOM_CTRL_1 = 0xC5
    VCOM_CTRL_2 = 0xC7
    POWER_CTRL_A = 0xCB
    POWER_CTRL_B = 0xCF
    READ_ID1 = 0xDA
    READ_ID2 = 0xDB
    READ_ID3 = 0xDC
    POS_GAMMA_CORRECTION = 0xE0
    NEG_GAMMA_CORRECTION = 0xE1
    DRIVER_TIMING_CTRL_A = 0xE8
    DRIVER_TIMING_CTRL_B = 0xEA
    POWER_ON_SEQ_CTRL = 0xED
    ENABLE_3G = 0xF2
    PUMP_RATIO_CTRL = 0xF7


LCD_INIT_SEQUENCE = [
    (LcdCommand.POWER_CTRL_A, [0x39, 0x2C, 0x00, 0x34, 0x02]),
    (LcdCommand.POWER_CTRL_B, [0x00, 0xC1, 0x30]),
    (LcdCommand.DRIVER_TIMING_CTRL_A, [0x85, 0x00, 0x78]),
    (LcdCommand.DRIVER_TIMING_CTRL_B, [0x00, 0x00]),
    (LcdCommand.POWER_ON_SEQ_CTRL, [0x64, 0x03, 0x12, 0x81]),
    (LcdCommand.PUMP_RATIO_CTRL, [0x20]),
    (LcdCommand.POWER_CTRL_1, [0x23]),
    (LcdCommand.POWER_CTRL_2, [0x10]),
    (LcdCommand.VCOM_CTRL_1, [0x3E, 0x28]),
    (LcdCommand.VCOM_CTRL_2, [0x86]),
    (LcdCommand.MEMORY_ACCESS_CTRL, [0x28]),
    (LcdCommand.COLMOD, [0x55]),
    (LcdCommand.FRAME_RATE_CTRL, [0x00, 0x18]),
    (LcdCommand.DISPLAY_FN_CTRL, [0x08, 0x82, 0x27]),
    (LcdCommand.ENABLE_3G, [0x00]),
    (LcdCommand.GAMMA_SET, [0x01]),
    (LcdCommand.POS_GAMMA_CORRECTION, [0x0F, 0x31, 0x2B, 0x0C, 0x0E, 0x08, 0x4E, 0xF1, 0x37, 0x07, 0x10, 0x03, 0x0E, 0x09, 0x00]),
    (LcdCommand.NEG_GAMMA_CORRECTION, [0x00, 0x0E, 0x14, 0x03, 0x11, 0x07, 0x31, 0xC1, 0x48, 0x08, 0x0F, 0x0C, 0x31, 0x36, 0x0F]),
    (LcdCommand.CASET, [0x00, 0x00, 0x01, 0x3F]),
    (LcdCommand.PASET, [0x00, 0x00, 0x00, 0xEF]),
    (LcdCommand.SLEEP_OUT, []),
    (LcdCommand.NOP, []),  # stand-in that means "wait 120ms"
    (LcdCommand.DISPLAY_ON, []),
]

LCD_INIT_ROM = []
for cmd, params in LCD_INIT_SEQUENCE:
    LCD_INIT_ROM.append(cmd)
    LCD_INIT_ROM.append(len(params))
    LCD_INIT_ROM.extend(params)
