#include <cassert>
#include <fstream>
#include <iostream>
#include <optional>

#include <cxxrtl/cxxrtl_vcd.h>
#include <newproject.h>

static cxxrtl_design::p_newproject top;
static cxxrtl::vcd_writer vcd;
static uint64_t vcd_time = 0;

static void step() {
  top.p_clock.set(true);
  top.step();
  vcd.sample(vcd_time++);
  top.p_clock.set(false);
  top.step();
  vcd.sample(vcd_time++);
}

int main(int argc, char **argv) {
  std::optional<std::string> vcd_out = std::nullopt;

  for (int i = 1; i < argc; ++i) {
    if (strcmp(argv[i], "--vcd") == 0 && argc >= (i + 2)) {
      vcd_out = std::string(argv[++i]);
    } else {
      std::cerr << "unknown argument \"" << argv[i] << "\"" << std::endl;
      return 2;
    }
  }

  if (vcd_out.has_value()) {
    debug_items di;
    top.debug_info(&di, nullptr, "top ");
    vcd.add(di);
  }

  top.p_reset.set(true);
  step();

  top.p_reset.set(false);

  // ledr should be low or high according to 'expected', where each element
  // represents 1/4th of a second. ledg should always be high.
  //
  // This mirrors the TopSpec test in Scala.
  int rc = 0;
  bool done = false;

  std::vector<int> expected = {0, 1, 1, 0, 0, 1, 1, 0};
  for (std::vector<int>::size_type i = 0; i < expected.size() && !done; ++i) {
    for (int j = 0; j < (CLOCK_HZ / 4); ++j) {
      if (top.p_io__ledr.get<int>() != expected[i]) {
        std::cerr << "unexpected ledr at i(" << i << "), j(" << j << ")"
                  << std::endl;
        rc = 1;
        done = true;
        break;
      }
      assert(top.p_io__ledg);

      step();
    }
  }

  std::cout << "finished on cycle " << (vcd_time >> 1) << std::endl;

  if (vcd_out.has_value()) {
    std::ofstream of(*vcd_out);
    of << vcd.buffer;
  }

  return rc;
}
