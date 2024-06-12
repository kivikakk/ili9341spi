const std = @import("std");

const options = @import("options");
const Cxxrtl = @import("./Cxxrtl.zig");

const UartConnector = @This();

divisor: u16,
tx_file: []const u8,
tx_running: bool = false,
tx_ix: usize = 0,

tx: Cxxrtl.Object(bool),
rx: Cxxrtl.Sample(bool),

tx_busy: bool = false,
tx_timer: u16 = 0,
tx_counter: u4 = 0,
tx_sr: u10 = 0,

pub fn init(cxxrtl: Cxxrtl, tx_file: []const u8) UartConnector {
    // swap names so they make sense for us here.
    const tx = cxxrtl.get(bool, "uart_rx");
    const rx = Cxxrtl.Sample(bool).init(cxxrtl, "uart_tx", true);

    tx.next(true);

    return .{
        .divisor = options.clock_hz / 115_200,
        .tx_file = tx_file,
        .tx = tx,
        .rx = rx,
    };
}

pub fn go(self: *UartConnector) void {
    self.tx_running = true;
    self.tx_ix = 0;
}

pub fn tick(self: *UartConnector) void {
    const rx = self.rx.tick();
    _ = rx;

    if (self.tx_busy) {
        self.tx.next((self.tx_sr & 1) == 1);
        if (self.tx_timer > 0) {
            self.tx_timer -= 1;
        } else {
            self.tx_timer = self.divisor - 1;
            self.tx_sr >>= 1;
            if (self.tx_counter > 0) {
                self.tx_counter -= 1;
            } else {
                self.tx_busy = false;
            }
        }
    } else if (self.tx_running) {
        if (self.tx_ix == self.tx_file.len) {
            self.tx_running = false;
            return;
        }

        self.tx_timer = self.divisor - 1;
        self.tx_counter = 9;
        self.tx_sr = (@as(u10, self.tx_file[self.tx_ix]) << 1) | 0b1_0000_0000_0;
        self.tx_busy = true;
        self.tx_ix += 1;
    }
}
