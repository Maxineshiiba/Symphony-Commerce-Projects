# -*- coding: utf-8 -*-
import math

#market price update in either yes or no side
def marketPriceUpdate(shares_sold_yes_side_so_far, shares_sold_no_side_so_far, market_price_yes, market_price_no, elasticity_constant):
	market_price_yes = float(market_price_yes)
	market_price_no = float(market_price_no)
	market_price = market_price_yes + market_price_no
	shares_sold_yes_side_so_far = float(shares_sold_yes_side_so_far)
	shares_sold_no_side_so_far = float(shares_sold_no_side_so_far)
	param_yes = shares_sold_yes_side_so_far/ elasticity_constant
	param_no = shares_sold_no_side_so_far/ elasticity_constant

	nominator_yes = market_price * math.exp(param_yes)
	nominator_no = market_price * math.exp(param_no)
	denominator = math.exp(param_yes) + math.exp(param_no)

	updated_yes_price = nominator_yes / denominator 
	updated_no_price = nominator_no / denominator

	return round(updated_yes_price, 1), round(updated_no_price, 1)

#assume market end date check passed, pending table doesnt have match
#assume unlimited market shares in all cases
#puchase shares on no side
def purchaseShares_No (market_price_yes, market_price_no, bidPrice, shares_want_to_buy, user_budget, shares_sold_yes_side_so_far, shares_sold_no_side_so_far, elasticity_constant):
	market_price_no = float(market_price_no)
	bidPrice = float(bidPrice)
	user_budget = float(user_budget)
	shares_want_to_buy = int(shares_want_to_buy)
	current_market_price = [market_price_yes, market_price_no]
	if (bidPrice >= market_price_no) & (market_price_no > 0) & (user_budget > shares_want_to_buy * bidPrice):
		shares_sold_no_side_so_far = shares_sold_no_side_so_far + shares_want_to_buy
		user_budget = user_budget - (shares_want_to_buy * bidPrice)
		updated_market_price = marketPriceUpdate(shares_sold_yes_side_so_far, shares_sold_no_side_so_far, market_price_yes, market_price_no, elasticity_constant)
		return updated_market_price, round(user_budget, 1)
	else:
		return current_market_price, round(user_budget, 1)

#purchase shares on yes side
def purchaseShares_Yes (market_price_yes, market_price_no, bidPrice, shares_want_to_buy, user_budget, shares_sold_yes_side_so_far, shares_sold_no_side_so_far, elasticity_constant):
	market_price_yes = float(market_price_yes)
	bidPrice = float(bidPrice)
	user_budget = float(user_budget)
	shares_want_to_buy = int(shares_want_to_buy)
	current_market_price = [market_price_yes, market_price_no]
	if (bidPrice >= market_price_yes) & (market_price_yes > 0) & (user_budget > shares_want_to_buy * bidPrice):
		shares_sold_yes_side_so_far = shares_sold_yes_side_so_far + shares_want_to_buy
		user_budget = user_budget - (shares_want_to_buy * bidPrice)
		updated_market_price = marketPriceUpdate(shares_sold_yes_side_so_far, shares_sold_no_side_so_far, market_price_yes, market_price_no, elasticity_constant)
		return updated_market_price, round(user_budget, 1)
	else:
		return current_market_price, round(user_budget, 1)

#sell shares on no side
def sellShares_No(market_price_yes, market_price_no, askPrice, shares_want_to_sell, user_budget, shares_sold_yes_side_so_far, shares_sold_no_side_so_far, elasticity_constant):
	market_price_no = float(market_price_no)
	askPrice = float(askPrice)
	user_budget = float(user_budget)
	shares_want_to_sell = int(shares_want_to_sell)
	current_market_price = [market_price_yes, market_price_no]
	if (shares_want_to_sell > 0) & (askPrice <= market_price_no) & (shares_sold_no_side_so_far > 0):
		shares_sold_no_side_so_far = shares_sold_no_side_so_far - shares_want_to_sell
		if shares_sold_no_side_so_far < 0: 
			shares_sold_no_side_so_far = 0
		user_budget = user_budget + (shares_want_to_sell * askPrice)
		updated_market_price = marketPriceUpdate(shares_sold_yes_side_so_far, shares_sold_no_side_so_far, market_price_yes, market_price_no, elasticity_constant)
		return updated_market_price, round(user_budget, 1)
	else:
		return current_market_price, round(user_budget, 1)

#sell shares on yes side
def sellShares_Yes(market_price_yes, market_price_no, askPrice, shares_want_to_sell, user_budget, shares_sold_yes_side_so_far, shares_sold_no_side_so_far, elasticity_constant):
	market_price_yes = float(market_price_yes)
	askPrice = float(askPrice)
	user_budget = float(user_budget)
	shares_want_to_sell = int(shares_want_to_sell)
	current_market_price = [market_price_yes, market_price_no]
	if (shares_want_to_sell > 0) & (askPrice <= market_price_yes) & (shares_sold_yes_side_so_far > 0):
		shares_sold_yes_side_so_far = shares_sold_yes_side_so_far - shares_want_to_sell
		if shares_sold_yes_side_so_far < 0: 
			shares_sold_yes_side_so_far = 0
		user_budget = user_budget + (shares_want_to_sell * askPrice)
		updated_market_price = marketPriceUpdate(shares_sold_yes_side_so_far, shares_sold_no_side_so_far, market_price_yes, market_price_no, elasticity_constant)
		return updated_market_price, round(user_budget, 1)
	else:
		return current_market_price, round(user_budget, 1)


#write another pending transaction with market price with offer price doesnt match any price in market
#if meet current market status, then complete transaction, otherwise stored in pending table

# sell price higher than market price or buy price lower than market price will be added in pending queue
def transaction(market_type, action, offer_price, quantity, budget, current_market_yes, current_market_no, current_share_yes, current_share_no, elasticity_constant):
	offer_price = float(offer_price)
	quantity = float(quantity)

	pending_bid_price_yes = [4321]
	pending_buy_shares_yes = [342]
	pending_bid_price_no = [1243]	
	pending_buy_shares_no = [114]

	pending_ask_price_yes = [22]
	pending_sell_shares_yes = [31]
	pending_ask_price_no = [456]
	pending_sell_shares_no = [245]		


	if ((market_type is 'Yes') & (action is 'Sell')):
		if(offer_price <= current_market_yes):
			return sellShares_Yes(current_market_yes, current_market_no, offer_price, quantity, budget, current_share_yes, current_share_no, elasticity_constant)
		else:
			pending_ask_price_yes.append(offer_price)
			pending_sell_shares_yes.append(quantity)
			budget = budget + offer_price * quantity
	if ((market_type is'No') & (action is 'Sell')):
		if(offer_price <= current_market_no):
			return sellShares_No(current_market_yes, current_market_no, offer_price, quantity, budget, current_share_yes, current_share_no, elasticity_constant)
		else:
			pending_ask_price_no.append(offer_price)
			pending_sell_shares_no.append(quantity)
			budget = budget + offer_price * quantity
	if ((market_type is 'Yes') & (action is 'Buy')):
		if((budget >= offer_price * quantity) & (offer_price >= current_market_yes)):
			return purchaseShares_Yes(current_market_yes, current_market_no, offer_price, quantity, budget, current_share_yes, current_share_no, elasticity_constant)
		elif((budget >= offer_price * quantity) & (offer_price < current_market_yes)):
			pending_bid_price_yes.append(offer_price)
			pending_buy_shares_yes.append(quantity)
			budget = budget - offer_price * quantity
		else:	
			print ('not enought budget to purchase')
	if ((market_type is 'No') & (action is 'Buy')):
		if ((budget > offer_price * quantity) & (offer_price >= current_market_no)):
			return purchaseShares_No(current_market_yes, current_market_no, offer_price, quantity, budget, current_share_yes, current_share_no, elasticity_constant)
		elif((budget >= offer_price * quantity) & (offer_price < current_market_no)):
			pending_bid_price_no.append(offer_price)
			pending_buy_shares_no.append(quantity)
			budget = budget - offer_price * quantity
		else:
			print ('not enough budget to purchase')

# need call user budget to update budget(cant do this part yet, missing user id etc)
# check all pending transactions at once, as long as there is an update in marketprice
# check if there is pending transaction could match current price  
# (pendingTransaction([20,40]), input is market price
def pendingTransaction(market_price):
	shares_sold_yes_side_so_far = 20
	shares_sold_no_side_so_far = 300
	elasticity_constant = 463
	user_budget_buy = 100000
	user_budget_sell = 100

	pending_bid_price_yes = [51,10,20]
	pending_buy_shares_yes = [12,6,10]
	pending_bid_price_no = [43,30,49]	
	pending_buy_shares_no = [5,6,10]

	pending_ask_price_yes = [20,19,45]
	pending_sell_shares_yes = [12,6,7]
	pending_ask_price_no = [51,39,40]
	pending_sell_shares_no = [5,12,1]		

	market_price_yes = market_price[0]
	market_price_no = market_price[1]

	index_bid_yes = find_index_greater(pending_bid_price_yes, market_price_yes)
	index_bid_no = find_index_greater(pending_bid_price_no, market_price_no)
	index_ask_yes = find_index_smaller(pending_ask_price_yes,market_price_yes)
	index_ask_no = find_index_smaller(pending_ask_price_no, market_price_no)
	
 	#matched price and shares buy_yes
 	matched_price_list_bid_yes = return_values(index_bid_yes, pending_bid_price_yes)
 	matched_shares_list_bid_yes = return_values(index_bid_yes, pending_buy_shares_yes)

 	#matched price and shares buy_no
 	matched_price_list_bid_no = return_values(index_bid_no, pending_bid_price_no)
 	matched_shares_list_bid_no = return_values(index_bid_no, pending_buy_shares_no)

	#matched price and shares sell_yes
	matched_price_list_ask_yes	= return_values(index_ask_yes, pending_ask_price_yes)
	matched_shares_list_ask_yes = return_values(index_ask_yes, pending_sell_shares_yes)

	#matched price and shares sell_no
	matched_price_list_ask_no = return_values(index_ask_no, pending_ask_price_no)
	matched_shares_list_ask_no = return_values(index_ask_no, pending_sell_shares_no)

	shares_sold_yes_side_so_far = shares_sold_yes_side_so_far + sum(matched_shares_list_bid_yes)
	shares_sold_yes_side_so_far = shares_sold_yes_side_so_far - sum(matched_shares_list_ask_yes)

	shares_sold_no_side_so_far = shares_sold_no_side_so_far + sum(matched_shares_list_bid_no)
	shares_sold_no_side_so_far = shares_sold_no_side_so_far - sum(matched_shares_list_ask_no)

	#remove matched elements from price lists
	matched_price_list_bid_yes = remove_elements(index_bid_yes, pending_bid_price_yes)
	matched_shares_list_bid_yes = remove_elements(index_bid_yes, pending_buy_shares_yes)

	matched_price_list_bid_no = remove_elements(index_bid_no, pending_bid_price_no)
	matched_shares_list_bid_no = remove_elements(index_bid_no, pending_buy_shares_no)

	matched_price_list_ask_yes = remove_elements(index_ask_yes, pending_ask_price_yes)
	matched_shares_list_ask_yes = remove_elements(index_ask_yes, pending_sell_shares_yes)

	matched_price_list_ask_no = remove_elements(index_ask_no, pending_ask_price_no)
	matched_shares_list_ask_no = remove_elements(index_ask_no, pending_sell_shares_no)

	return marketPriceUpdate(shares_sold_yes_side_so_far, shares_sold_no_side_so_far, market_price_yes, market_price_no, elasticity_constant)

#remove multiple elements from list
def remove_elements(elements, list):
	for i in elements:
		list[i] = '!'
	for i in range(0, list.count('!')):
		list.remove('!')	
	return list
	
#return list of shares that match price
def return_values(index, shares):
	list = []
	for x in index:
		list.append(shares[x])
	return list 

#return index of values in the list that match the criteria	
def find_index_greater (lst, a):
	return [i for i, x in enumerate(lst) if x >= a]

def find_index_smaller (lst, a):
	return [i for i, x in enumerate(lst) if x <= a]

def main():

#	print return_values([2,3,4], [22,33,22,11,232])
#	print(marketPriceUpdate (174,0, 32, 68, 463))
#	print(purchaseShares_Yes(20, 10, 10,30, 10,5000, 99, 463))
#	print(purchaseShares_No(220,220,125,20,0,20,0,463))
#	print(pendingTransaction(([20,40])))
#	print (find([20, 8,30,9], 10))
#	print(pendingTransaction(500,150))
#	print amountSharesUserCanBuy(100, 5000)
#	transaction(market_type, action, offer_price, quantity, budget, current_market_yes, current_market_no, current_share_yes, current_share_no, elasticity_constant)	
	print transaction ('No', 'Buy', 28, 30, 1000, 10,20,32,33,466)

if __name__ == '__main__':
	main()	
