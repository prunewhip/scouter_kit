# Host tests (T1): cmake -S native -B BUILD -DTHIRD_PARTY_DIR=OUT && cmake --build BUILD && ctest --test-dir BUILD
add_executable(mosh_client_test mosh_client_test.cpp)
target_link_libraries(mosh_client_test PRIVATE ssh_core mosh)
add_test(NAME mosh_client COMMAND mosh_client_test)
add_executable(keys_test keys_test.cpp ../keys.cpp)
add_test(NAME keys COMMAND keys_test)
add_executable(terminal_test terminal_test.cpp)
target_link_libraries(terminal_test PRIVATE ssh_core mosh)
target_compile_definitions(terminal_test PRIVATE TERM_FONT="${SCOUTER_KIT}/go/examples/term/fonts/departure_mono_nerd.skf")
add_test(NAME terminal COMMAND terminal_test)
add_executable(screen_test screen_test.cpp)
target_link_libraries(screen_test PRIVATE ssh_core mosh)
add_test(NAME screen COMMAND screen_test)
add_executable(fx_test fx_test.cpp)
target_link_libraries(fx_test PRIVATE ssh_core mosh)
add_test(NAME fx COMMAND fx_test)
# The glasses VM core (src/vm) and VIDEOX (g1v0 0.5.62+): the core's own suite, the library's encoder against the
# core, and a short fuzz of the new decoder's checks
add_executable(vm_test ${SCOUTER_KIT}/tests/vm_test.c ${SCOUTER_KIT}/tests/vm_v4_ref.c ${SCOUTER_KIT}/src/vm/vm.c
  ${SCOUTER_KIT}/src/vm/vm_gfx.c)
target_include_directories(vm_test PRIVATE ${SCOUTER_KIT}/src/vm)
add_test(NAME vm COMMAND vm_test)
add_executable(videox_test ${SCOUTER_KIT}/tests/videox_test.cpp)
target_link_libraries(videox_test PRIVATE scouter_kit Threads::Threads)
add_test(NAME videox COMMAND videox_test)
# VIDEOT (g1v0 0.5.68+): the transform-video encoder against the core's decoder, bit for bit; random records
add_executable(videot_test ${SCOUTER_KIT}/tests/videot_test.cpp)
target_link_libraries(videot_test PRIVATE scouter_kit Threads::Threads)
add_test(NAME videot COMMAND videot_test)
add_executable(videox_fuzz ${SCOUTER_KIT}/tests/videox_fuzz.c ${SCOUTER_KIT}/src/vm/vm_gfx.c)
add_test(NAME videox_fuzz COMMAND videox_fuzz 50000)
# strict C11 (as the VM core's own builds): with GNU extensions glibc declares getw, which vm_test defines
set_target_properties(vm_test videox_fuzz PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
