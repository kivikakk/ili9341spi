const std = @import("std");

const options = @import("options");
const Cxxrtl = @import("./Cxxrtl.zig");

const UartConnector = @This();

file: []const u8,
divisor: u16,
running: bool = false,
ix: usize = 0,

tx: Cxxrtl.Object(bool),

busy: bool = false,
timer: u16 = 0,
counter: u4 = 0,
sr: u10 = 0,

pub fn init(cxxrtl: Cxxrtl, file: []const u8) UartConnector {
    const rx = cxxrtl.get(bool, "uart_rx");

    rx.next(true);

    return .{
        .file = file,
        .divisor = options.clock_hz / 115_200,
        .tx = rx,
    };
}

pub fn go(self: *UartConnector) void {
    self.running = true;
    self.ix = 0;
}

pub fn tick(self: *UartConnector) void {
    if (self.busy) {
        self.tx.next((self.sr & 1) == 1);
        if (self.timer > 0) {
            self.timer -= 1;
        } else {
            self.timer = self.divisor - 1;
            self.sr >>= 1;
            if (self.counter > 0) {
                self.counter -= 1;
            } else {
                self.busy = false;
            }
        }
    } else if (self.running) {
        if (self.ix == self.file.len) {
            self.running = false;
            return;
        }

        self.timer = self.divisor - 1;
        self.counter = 9;
        self.sr = (@as(u10, self.file[self.ix]) << 1) | 0b1_0000_0000_0;
        self.busy = true;
        self.ix += 1;
    }
}
