<?php
// This is a single-line comment

/* 
This is a 
multi-line comment
*/

// Display text on the webpage
echo "Hello, World!";

// Variables
$name = "Andrei";
$age = 18;

// Concatenate and display variables
echo "<br>My name is " . $name . " and I am " . $age . " years old.";

// Simple arithmetic
$num1 = 10;
$num2 = 5;
$sum = $num1 + $num2;
echo "<br>The sum of $num1 and $num2 is $sum.";

// Conditional statement
if ($age >= 18) {
    echo "<br>You are an adult.";
} else {
    echo "<br>You are a minor.";
}

// Loop example
for ($i = 1; $i <= 5; $i++) {
    echo "<br>Number: $i";
}
?>