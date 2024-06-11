const std = @import("std");

const Cxxrtl = @import("./Cxxrtl.zig");
const SimController = @import("./SimController.zig");
const SpiConnector = @import("./SpiConnector.zig");
const UartConnector = @import("./UartConnector.zig");

const SimThread = @This();

pub const WIDTH = 320;
pub const HEIGHT = 240;
pub const Color = packed struct(u32) { r: u8, g: u8, b: u8, a: u8 };
pub const ImgData = [HEIGHT * WIDTH]Color;

sim_controller: *SimController,
alloc: std.mem.Allocator,

cxxrtl: Cxxrtl,
vcd: ?Cxxrtl.Vcd,

clock: Cxxrtl.Object(bool),
reset: Cxxrtl.Object(bool),

spi_connector: SpiConnector,
uart_connector: UartConnector,

img_data: ImgData = [_]Color{.{ .r = 0, .g = 0, .b = 0, .a = 255 }} ** (HEIGHT * WIDTH),
img_data_new: bool = true,

pub fn init(alloc: std.mem.Allocator, sim_controller: *SimController) SimThread {
    const cxxrtl = Cxxrtl.init();

    var vcd: ?Cxxrtl.Vcd = null;
    if (sim_controller.vcd_out != null) vcd = Cxxrtl.Vcd.init(cxxrtl);

    const clock = cxxrtl.get(bool, "clock");
    const reset = cxxrtl.get(bool, "reset");

    const spi_connector = SpiConnector.init(cxxrtl);
    const uart_connector = UartConnector.init(cxxrtl, @embedFile("rom.bin"));

    return .{
        .sim_controller = sim_controller,
        .alloc = alloc,
        .cxxrtl = cxxrtl,
        .vcd = vcd,
        .clock = clock,
        .reset = reset,
        .spi_connector = spi_connector,
        .uart_connector = uart_connector,
    };
}

pub fn deinit(self: *SimThread) void {
    if (self.vcd) |*vcd| vcd.deinit();
    self.cxxrtl.deinit();
}

pub fn run(self: *SimThread) !void {
    self.sim_controller.lock();
    self.reset.next(true);
    self.cycle();
    self.reset.next(false);
    self.sim_controller.unlock();

    // XXX: We handle barely any of MADCTL, so this is incredibly specific to
    // our design for now.

    var rot: bool = false;
    var sc: u16 = 0x0000;
    var ec: u16 = 0x00ef;
    var sp: u16 = 0x0000;
    var ep: u16 = 0x013f;

    var col: u16 = sc;
    var pag: u16 = sp;

    var state: union(enum) {
        Idle,
        Caset: struct { ix: u2 = 0, sc: u16 = 0, ec: u16 = 0 },
        Paset: struct { ix: u2 = 0, sp: u16 = 0, ep: u16 = 0 },
        MadCtl,
        MemoryWriteA,
        MemoryWriteB: u8,
    } = .Idle;

    while (self.sim_controller.lockIfRunning()) {
        defer self.sim_controller.unlock();
        self.cycle();

        switch (self.spi_connector.tick()) {
            .Nop => {},
            .Command => |cmd| {
                if (state != .Idle and state != .MemoryWriteA) {
                    std.debug.panic("got command {x:0>2} in state {s}", .{ cmd, @tagName(state) });
                }

                if (cmd == 0x36) {
                    state = .MadCtl;
                } else if (cmd == 0x2a) {
                    state = .{ .Caset = .{} };
                } else if (cmd == 0x2b) {
                    state = .{ .Paset = .{} };
                } else if (cmd == 0x2c) {
                    state = .MemoryWriteA;
                    col = sc;
                    pag = sp;
                    self.uart_connector.go();
                } else {
                    state = .Idle;
                }
            },
            .Data => |data| {
                switch (state) {
                    .Idle => {}, // unhandled command
                    .Caset => |*d| {
                        state = switch (d.ix) {
                            0 => .{ .Caset = .{ .ix = 1, .sc = (@as(u16, data) << 8) } },
                            1 => .{ .Caset = .{ .ix = 2, .sc = d.sc | data } },
                            2 => .{ .Caset = .{ .ix = 3, .sc = d.sc, .ec = (@as(u16, data) << 8) } },
                            3 => s: {
                                sc = d.sc;
                                ec = d.ec | data;
                                std.debug.print("CASET: {x:0>4}..{x:0>4} ({d}..{d})\n", .{ sc, ec, sc, ec });
                                break :s .Idle;
                            },
                        };
                    },
                    .Paset => |*d| {
                        state = switch (d.ix) {
                            0 => .{ .Paset = .{ .ix = 1, .sp = (@as(u16, data) << 8) } },
                            1 => .{ .Paset = .{ .ix = 2, .sp = d.sp | data } },
                            2 => .{ .Paset = .{ .ix = 3, .sp = d.sp, .ep = (@as(u16, data) << 8) } },
                            3 => s: {
                                sp = d.sp;
                                ep = d.ep | data;
                                std.debug.print("PASET: {x:0>4}..{x:0>4} ({d}..{d})\n", .{ sp, ep, sp, ep });
                                break :s .Idle;
                            },
                        };
                    },
                    .MadCtl => {
                        rot = (data & 0b00100000) != 0;
                        state = .Idle;
                    },
                    .MemoryWriteA => {
                        state = .{ .MemoryWriteB = data };
                    },
                    .MemoryWriteB => |data0| {
                        const r: u5 = @truncate((data0 & 0b11111000) >> 3);
                        const g: u6 = @truncate(((data0 & 0b111) << 3) | (data >> 5));
                        const b: u5 = @truncate(data & 0b00011111);

                        self.img_data[@as(usize, pag) * WIDTH + col] = .{
                            .r = @as(u8, r) << 3,
                            .g = @as(u8, g) << 2,
                            .b = @as(u8, b) << 3,
                            .a = 255,
                        };
                        self.img_data_new = true;

                        state = .MemoryWriteA;

                        if (col == ec) {
                            if (pag == ep) {
                                col = sc;
                                pag = sp;
                                // Just finish for now, iirc MADCTL varies this.
                                state = .Idle;
                                std.debug.print("MemoryWriteB wrapped\n", .{});
                            } else {
                                pag += 1;
                                col = sc;
                            }
                        } else {
                            col += 1;
                        }
                    },
                }
            },
        }
        self.uart_connector.tick();
    }

    try self.writeVcd();
}

fn cycle(self: *SimThread) void {
    self.clock.next(false);
    self.cxxrtl.step();
    if (self.vcd) |*vcd| vcd.sample();

    self.clock.next(true);
    self.cxxrtl.step();
    if (self.vcd) |*vcd| vcd.sample();

    self.sim_controller.cycle_number += 1;
}

fn writeVcd(self: *SimThread) !void {
    if (self.vcd) |*vcd| {
        const buffer = try vcd.read(self.alloc);
        defer self.alloc.free(buffer);

        var file = try std.fs.cwd().createFile(self.sim_controller.vcd_out.?, .{});
        defer file.close();

        try file.writeAll(buffer);
    }
}
