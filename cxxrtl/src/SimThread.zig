const std = @import("std");

const Cxxrtl = @import("./Cxxrtl.zig");
const SimController = @import("./SimController.zig");
const SpiConnector = @import("./SpiConnector.zig");

const SimThread = @This();

sim_controller: *SimController,
alloc: std.mem.Allocator,

cxxrtl: Cxxrtl,
vcd: ?Cxxrtl.Vcd,

clock: Cxxrtl.Object(bool),
reset: Cxxrtl.Object(bool),
uart_rx: Cxxrtl.Object(bool),

spi_connector: SpiConnector,

pub fn init(alloc: std.mem.Allocator, sim_controller: *SimController) SimThread {
    const cxxrtl = Cxxrtl.init();

    var vcd: ?Cxxrtl.Vcd = null;
    if (sim_controller.vcd_out != null) vcd = Cxxrtl.Vcd.init(cxxrtl);

    const clock = cxxrtl.get(bool, "clock");
    const reset = cxxrtl.get(bool, "reset");
    const uart_rx = cxxrtl.get(bool, "uart_rx");

    const spi_connector = SpiConnector.init(cxxrtl);

    return .{
        .sim_controller = sim_controller,
        .alloc = alloc,
        .cxxrtl = cxxrtl,
        .vcd = vcd,
        .clock = clock,
        .reset = reset,
        .uart_rx = uart_rx,
        .spi_connector = spi_connector,
    };
}

pub fn deinit(self: *SimThread) void {
    if (self.vcd) |*vcd| vcd.deinit();
    self.cxxrtl.deinit();
}

pub fn run(self: *SimThread) !void {
    self.uart_rx.next(true);

    self.sim_controller.lock();
    self.reset.next(true);
    self.cycle();
    self.reset.next(false);
    self.sim_controller.unlock();

    while (self.sim_controller.lockIfRunning()) {
        defer self.sim_controller.unlock();
        self.cycle();
        self.spi_connector.tick();
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
