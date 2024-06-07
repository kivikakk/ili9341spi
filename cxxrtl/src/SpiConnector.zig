const std = @import("std");

const Cxxrtl = @import("./Cxxrtl.zig");

const SpiConnector = @This();

cipo: Cxxrtl.Object(bool),
blk: Cxxrtl.Sample(bool),
dc: Cxxrtl.Sample(bool),
res: Cxxrtl.Sample(bool),
copi: Cxxrtl.Sample(bool),
clk: Cxxrtl.Sample(bool),

sr: u8 = 0,
index: u8 = 0,

pub fn init(cxxrtl: Cxxrtl) SpiConnector {
    const cipo = cxxrtl.get(bool, "bb_cipo");
    const blk = Cxxrtl.Sample(bool).init(cxxrtl, "bb_blk", false);
    const dc = Cxxrtl.Sample(bool).init(cxxrtl, "bb_dc", false);
    const res = Cxxrtl.Sample(bool).init(cxxrtl, "bb_res", false);
    const copi = Cxxrtl.Sample(bool).init(cxxrtl, "bb_copi", false);
    const clk = Cxxrtl.Sample(bool).init(cxxrtl, "bb_clk", false);

    return .{
        .cipo = cipo,
        .blk = blk,
        .dc = dc,
        .res = res,
        .copi = copi,
        .clk = clk,
    };
}

pub fn tick(self: *SpiConnector) void {
    const dc = self.dc.tick();
    const res = self.res.tick();
    const copi = self.copi.tick();
    const clk = self.clk.tick();

    if (res.curr) {
        self.sr = 0;
        self.index = 0;
    }

    if (clk.rising()) {
        self.sr = (self.sr << 1) | @as(u8, (if (copi.curr) 1 else 0));
        if (self.index < 7)
            self.index += 1
        else if (dc.curr) {
            // self.sr is command
            self.index = 0;
        } else {
            // self.sr is data
            self.index = 0;
        }
    }
}
