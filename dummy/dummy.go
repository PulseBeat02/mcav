// dummy.go
package main

import "C"
import "fmt"

//export dummy_function
func dummy_function(a, b int) int {
    return a + b
}

//export dummy_print
func dummy_print(message *C.char) {
    fmt.Printf("Dummy message: %s\n", C.GoString(message))
}

func main() {
    // Required main function
}