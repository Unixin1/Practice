firstNum = float(input("Enter the First Number: "))
secondNum = float(input("Enter the Second Number: "))

operator = input("choose the Operator \n\n1.Addition\n2.Subtraction\n3.Multiplication\n4.Division\n\nChoose: ")

if operator == "1":
    print("Answer:", firstNum + secondNum)

elif operator == "2":
    print("Answer:", firstNum - secondNum)

elif operator == "3":
    print("Answer:", firstNum * secondNum)

elif operator == "4":
    print("Answer:", firstNum / secondNum)

else:
    print("Invalid choice.")